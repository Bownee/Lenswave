package com.bownee.lenswave.proton

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import java.util.concurrent.TimeUnit

/**
 * Drains the thumbnail and preview queues while a validated unmetered network is available.
 * Every outcome except a crash ends the run successfully: a WorkManager retry cannot start
 * from the background, so a run that leaves work behind enqueues its own follow-up request
 * instead ([ProtonThumbnailFollowUpPolicy]), under the same unique name and with the
 * constraints and delay that describe what it is waiting for.
 */
class ProtonThumbnailWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val entryPoint =
            EntryPointAccessors.fromApplication(
                applicationContext,
                RepositoryEntryPoint::class.java,
            )
        // The user paused downloads from the notification; a request that slipped past the
        // scheduler's check (one already queued when the pause was set) ends here.
        if (entryPoint.pauseStore().isPaused(UserId(userId))) return finish(ProtonThumbnailWorkOutcome.PAUSED)
        val runGuard = entryPoint.runGuard()
        // Unique work de-duplicates per name and per WorkManager instance; this is the
        // process-wide guarantee that two batch loops never share the queues, and so that the
        // release of stale claims below the gateway only ever comes from the single active run.
        if (!runGuard.tryBegin()) return finish(ProtonThumbnailWorkOutcome.ALREADY_RUNNING)
        var networkMonitor: ProtonThumbnailNetworkMonitor? = null
        val previewAdmission = entryPoint.previewAdmission()
        // The downloader asks between the chunks of a preview batch whether previews are still
        // allowed, so a batch a glance at the app authorised does not outlive the glance.
        previewAdmission.bind(::previewsAllowed)
        return try {
            val requestedUserId = UserId(userId)
            val session =
                withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
                    entryPoint.accountSessionManager().state.first { state ->
                        state.initialized && !state.transitioning
                    }
                } ?: return finish(ProtonThumbnailWorkOutcome.SESSION_UNAVAILABLE)
            if (session.activeUserId != requestedUserId) {
                return Result.failure()
            }
            val run = Run(entryPoint, requestedUserId)
            val initialProgress = run.repository.thumbnailWorkProgress(requestedUserId)
            if (!ProtonThumbnailWorkPolicy.hasPendingWork(initialProgress, previewsAllowed())) {
                val outcome =
                    if (initialProgress.previewsPending == 0) {
                        ProtonThumbnailWorkOutcome.COMPLETE
                    } else {
                        ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED
                    }
                return run.end(outcome, initialProgress, lastIdle = null)
            }
            val monitor = ProtonThumbnailNetworkMonitor(applicationContext).also { networkMonitor = it }
            if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                return run.end(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, initialProgress, lastIdle = null)
            }
            val foregroundInfoFactory = ProtonThumbnailForegroundInfoFactory(applicationContext, requestedUserId)
            publishForeground(foregroundInfoFactory, initialProgress.notificationProgress(), force = true)
            var lastIdle: ProtonThumbnailQueueStep.Idle? = null
            val outcome =
                withTimeoutOrNull(ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS) {
                    var runOutcome: ProtonThumbnailWorkOutcome
                    while (true) {
                        if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                            runOutcome = ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK
                            break
                        }
                        // Media the user opened comes first. The wait is short and the answer
                        // is an idle step, so the loop below decides what a busy viewer means
                        // for this run instead of the claim sitting on the viewer's download.
                        val step =
                            if (!run.transferCoordinator.awaitNoForegroundTransfer(FOREGROUND_YIELD_TIMEOUT_MILLIS)) {
                                ProtonThumbnailWorkPolicy.foregroundBusyStep()
                            } else {
                                run.repository.downloadNextQueuedThumbnailBatch(
                                    requestedUserId,
                                    previewsAllowed(),
                                ) { progress ->
                                    publishForeground(foregroundInfoFactory, progress.notificationProgress())
                                }
                            }
                        when (step) {
                            ProtonThumbnailQueueStep.Processed -> {
                                run.processedBatch = true
                            }

                            is ProtonThumbnailQueueStep.Idle -> {
                                // Every idle step is remembered, the one before a sleep too: a
                                // run that ends on a timeout or a lost network still owes the
                                // charging follow-up for the previews it saw deferred.
                                lastIdle = step
                                val wait = ProtonBackgroundBatchPolicy.idleWaitMillis(step)
                                if (wait != null) {
                                    // A retry due within seconds is worth sleeping for; a longer
                                    // backoff ends the run and becomes the follow-up's initial
                                    // delay, so the wakelock is not held for it.
                                    delay(wait + IDLE_WAIT_SLACK_MILLIS)
                                    continue
                                }
                                runOutcome =
                                    when {
                                        step.hasPending -> ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY
                                        step.previewsDeferred -> ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED
                                        else -> ProtonThumbnailWorkOutcome.COMPLETE
                                    }
                                break
                            }
                        }
                    }
                    runOutcome
                } ?: ProtonThumbnailWorkOutcome.TIMED_OUT
            val finalProgress = run.repository.thumbnailWorkProgress(requestedUserId)
            publishForeground(foregroundInfoFactory, finalProgress.notificationProgress(), force = true)
            run.end(outcome, finalProgress, lastIdle)
        } catch (error: CancellationException) {
            reportState("interrupted")
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.THUMBNAIL_WORKER, error)
            if (ProtonThumbnailWorkPolicy.shouldRetryAfterError(runAttemptCount)) {
                reportState("retry-error")
                Result.retry()
            } else {
                reportState("stopped-error")
                Result.failure()
            }
        } finally {
            previewAdmission.unbind()
            networkMonitor?.close()
            runGuard.end()
        }
    }

    /** What one admitted run needs, and how it ends: with the follow-up its outcome calls for. */
    private inner class Run(
        entryPoint: RepositoryEntryPoint,
        private val userId: UserId,
    ) {
        val repository = entryPoint.thumbnailWork()

        /** The network backoff ladder this run was started on; see [ProtonThumbnailFollowUpPolicy]. */
        private val networkWaitAttempt = inputData.getInt(KEY_NETWORK_WAIT_ATTEMPT, 0)

        /** A run that got a batch through resets the network backoff ladder. */
        var processedBatch = false
        val transferCoordinator = entryPoint.transferCoordinator()
        private val followUps = entryPoint.followUpScheduler()

        /**
         * Enqueues the follow-up before returning, while this run is still the running job under
         * the unique name, so the scheduler chains it rather than dropping or cancelling it.
         */
        fun end(
            outcome: ProtonThumbnailWorkOutcome,
            progress: ProtonThumbnailWorkProgress,
            lastIdle: ProtonThumbnailQueueStep.Idle?,
        ): Result {
            val allowPreviews = previewsAllowed()
            val followUp =
                ProtonThumbnailFollowUpPolicy.followUp(
                    outcome,
                    workRemaining = ProtonThumbnailWorkPolicy.hasPendingWork(progress, allowPreviews),
                    previewsDeferred =
                        lastIdle?.previewsDeferred == true || (!allowPreviews && progress.previewsPending > 0),
                    retryAfterMillis = lastIdle?.retryAfterMillis,
                    networkWaitAttempt = if (processedBatch) 0 else networkWaitAttempt,
                )
            if (followUp != null) followUps.enqueueFollowUp(userId, followUp)
            return finish(outcome)
        }
    }

    private var foregroundUnavailable = false
    private var lastForegroundPublishMillis: Long? = null

    private val batteryManager by lazy { applicationContext.getSystemService(BatteryManager::class.java) }
    private var previewsAllowedCheckedAtMillis: Long? = null
    private var previewsAllowedCached = false

    /**
     * Previews are a gigabyte for a large library, so they wait for the charger unless the app is
     * on screen; thumbnails are small enough to download whenever unmetered network is available.
     * The answer is a binder round trip and an allocation, asked once per loop iteration, so it
     * is cached for a few seconds: a charger or a screen change a moment late costs nothing.
     */
    private fun previewsAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (ProtonThumbnailWorkPolicy.isPreviewsAllowedFresh(previewsAllowedCheckedAtMillis, now)) {
            return previewsAllowedCached
        }
        val allowed =
            ProtonThumbnailWorkPolicy.previewsAllowed(
                charging = batteryManager?.isCharging == true,
                appVisible =
                    ActivityManager
                        .RunningAppProcessInfo()
                        .also(ActivityManager::getMyMemoryState)
                        .importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            )
        previewsAllowedCached = allowed
        previewsAllowedCheckedAtMillis = now
        return allowed
    }

    /**
     * Promotes the run to a foreground service so it can outlive the ten-minute background limit.
     * Android 12+ refuses that while the app is in the background; rather than failing the run,
     * it continues without a notification and lets the platform stop it at the background limit.
     *
     * Progress updates are rate limited: re-posting the notification after every few files is
     * wasted work for the system and the battery.
     */
    private suspend fun publishForeground(
        factory: ProtonThumbnailForegroundInfoFactory,
        progress: ProtonThumbnailNotificationProgress,
        force: Boolean = false,
    ) {
        if (foregroundUnavailable) return
        val now = SystemClock.elapsedRealtime()
        if (!ProtonThumbnailWorkPolicy.shouldPublishProgress(lastForegroundPublishMillis, now, force)) return
        try {
            setForeground(factory.create(progress))
            lastForegroundPublishMillis = now
        } catch (error: IllegalStateException) {
            if (!ProtonThumbnailWorkPolicy.isForegroundStartRefusal(error)) throw error
            foregroundUnavailable = true
            reportState("background-only")
        }
    }

    private fun finish(outcome: ProtonThumbnailWorkOutcome): Result {
        reportState(outcome.diagnosticState)
        return Result.success()
    }

    private fun reportState(state: String) {
        LenswaveDiagnostics.reportState(
            operation = LenswaveOperation.THUMBNAIL_WORKER,
            state = state,
            attempt = (runAttemptCount + 1).coerceAtMost(ProtonThumbnailWorkPolicy.MAX_ERROR_ATTEMPTS),
            maximumAttempts = ProtonThumbnailWorkPolicy.MAX_ERROR_ATTEMPTS,
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RepositoryEntryPoint {
        fun thumbnailWork(): ProtonThumbnailWorkGateway

        fun accountSessionManager(): ProtonAccountSessionManager

        fun followUpScheduler(): ProtonThumbnailFollowUpScheduler

        fun runGuard(): ProtonThumbnailRunGuard

        fun transferCoordinator(): ProtonTransferCoordinator

        fun pauseStore(): ProtonThumbnailPauseStore

        fun previewAdmission(): ProtonPreviewAdmission
    }

    companion object {
        const val KEY_USER_ID = "user-id"
        const val KEY_NETWORK_WAIT_ATTEMPT = "network-wait-attempt"
        private const val SESSION_READY_TIMEOUT_MILLIS = 30_000L
        private const val NETWORK_READY_TIMEOUT_MILLIS = 5_000L
        private const val FOREGROUND_YIELD_TIMEOUT_MILLIS = 5_000L
        private const val IDLE_WAIT_SLACK_MILLIS = 1_000L

        fun request(
            userId: UserId,
            requiresCharging: Boolean = false,
            initialDelayMillis: Long = 0L,
            networkWaitAttempt: Int = 0,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProtonThumbnailWorker>()
                .setInputData(workDataOf(KEY_USER_ID to userId.id, KEY_NETWORK_WAIT_ATTEMPT to networkWaitAttempt))
                .setConstraints(
                    Constraints
                        .Builder()
                        // Unmetered is what the run needs, so a request enqueued without Wi-Fi
                        // costs nothing until there is some. The constraint is not the whole
                        // check: JobScheduler knows nothing about validation, and a network
                        // constraint can be revoked as the phone enters Doze, so the worker
                        // also checks for validated unmetered access between batches.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiresCharging(requiresCharging)
                        .build(),
                ).setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
                .build()
    }
}

/** How a run ended; every one of these is a successful WorkManager result. */
internal enum class ProtonThumbnailWorkOutcome(
    val diagnosticState: String,
) {
    COMPLETE("complete"),
    WAITING_FOR_NETWORK("waiting-network"),
    WAITING_FOR_RETRY("waiting-retry"),
    PREVIEWS_DEFERRED("previews-deferred"),
    TIMED_OUT("timeout"),
    SESSION_UNAVAILABLE("session-unavailable"),

    /** Another run of this process holds the queues; it schedules whatever follow-up is due. */
    ALREADY_RUNNING("already-running"),

    /** The user paused background downloads from the notification; a manual refresh lifts that. */
    PAUSED("paused"),
}

internal object ProtonThumbnailWorkPolicy {
    /** A run that crashes is retried a couple of times by WorkManager, then left to the next enqueue. */
    const val MAX_ERROR_ATTEMPTS = 3

    /**
     * Android 15 gives a dataSync foreground service six hours in every 24 across all its runs;
     * a run that used five and a half of them left nothing for the day. Two hours downloads a
     * large library's thumbnails and, when work remains, ends with a follow-up under the same
     * unique name ([ProtonThumbnailFollowUpPolicy]) rather than with the budget spent.
     */
    const val MAX_RUN_MILLIS = 2L * 60L * 60L * 1_000L
    const val PROGRESS_PUBLISH_INTERVAL_MILLIS = 1_500L

    /**
     * `ForegroundServiceStartNotAllowedException` (API 31+) extends IllegalStateException; it is
     * matched by name so the check compiles and tests run on the JVM.
     */
    fun isForegroundStartRefusal(error: Throwable): Boolean =
        error::class.java.simpleName == "ForegroundServiceStartNotAllowedException"

    fun shouldRetryAfterError(runAttemptCount: Int): Boolean = runAttemptCount + 1 < MAX_ERROR_ATTEMPTS

    /** Previews wait for the charger unless the app is on screen; thumbnails never wait. */
    fun previewsAllowed(
        charging: Boolean,
        appVisible: Boolean,
    ): Boolean = charging || appVisible

    /** How long a [previewsAllowed] answer is reused before the charger and the screen are asked again. */
    const val PREVIEWS_ALLOWED_CACHE_MILLIS = 5_000L

    fun isPreviewsAllowedFresh(
        checkedAtMillis: Long?,
        nowMillis: Long,
    ): Boolean = checkedAtMillis != null && nowMillis - checkedAtMillis < PREVIEWS_ALLOWED_CACHE_MILLIS

    fun hasPendingWork(
        progress: ProtonThumbnailWorkProgress,
        allowPreviews: Boolean,
    ): Boolean = progress.pending > 0 || (allowPreviews && progress.previewsPending > 0)

    /** How soon a run asks again after a foreground transfer kept it from claiming a batch. */
    const val FOREGROUND_BUSY_RETRY_MILLIS = 3_000L

    /**
     * The step a run takes while the viewer is downloading: the queues were not consulted, so
     * the work is still pending and worth asking about again shortly.
     */
    fun foregroundBusyStep(): ProtonThumbnailQueueStep.Idle =
        ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = FOREGROUND_BUSY_RETRY_MILLIS)

    /** The notification is re-posted at most every [PROGRESS_PUBLISH_INTERVAL_MILLIS] unless forced. */
    fun shouldPublishProgress(
        lastPublishedAtMillis: Long?,
        nowMillis: Long,
        force: Boolean,
    ): Boolean =
        force ||
            lastPublishedAtMillis == null ||
            nowMillis - lastPublishedAtMillis >= PROGRESS_PUBLISH_INTERVAL_MILLIS
}
