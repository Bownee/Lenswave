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
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        val requestedUserId = UserId(userId)
        return try {
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
            var lastProgress = initialProgress
            publishForeground(foregroundInfoFactory, lastProgress.notificationProgress(), force = true)
            var lastIdle: ProtonThumbnailQueueStep.Idle? = null
            var consecutiveBusySteps = 0
            val outcome =
                withTimeoutOrNull(ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS) {
                    var runOutcome: ProtonThumbnailWorkOutcome
                    while (true) {
                        // Without the notification the platform stops the job at ten minutes,
                        // and a refusal may mean the day's foreground allowance is spent: the
                        // run ends on its own terms, with a follow-up that waits it out.
                        if (ProtonThumbnailWorkPolicy.backgroundOnlyDeadlineReached(
                                foregroundUnavailableSinceMillis,
                                SystemClock.elapsedRealtime(),
                            )
                        ) {
                            runOutcome = ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE
                            break
                        }
                        if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                            runOutcome = ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK
                            break
                        }
                        // Media the user opened comes first. The wait is short and the answer
                        // is an idle step, so the loop below decides what a busy viewer means
                        // for this run instead of the claim sitting on the viewer's download.
                        // A viewer that stays busy ends the run rather than keeping the
                        // foreground service awake to ask every few seconds.
                        val step =
                            if (!run.transferCoordinator.awaitNoForegroundTransfer(FOREGROUND_YIELD_TIMEOUT_MILLIS)) {
                                consecutiveBusySteps++
                                publishForeground(
                                    foregroundInfoFactory,
                                    lastProgress.notificationProgress().copy(yielding = true),
                                )
                                ProtonThumbnailWorkPolicy.foregroundBusyStep(consecutiveBusySteps)
                            } else {
                                if (consecutiveBusySteps > 0) {
                                    consecutiveBusySteps = 0
                                    publishForeground(foregroundInfoFactory, lastProgress.notificationProgress())
                                }
                                run.repository.downloadNextQueuedThumbnailBatch(
                                    requestedUserId,
                                    previewsAllowed(),
                                ) { progress ->
                                    lastProgress = progress
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
            // The time under the foreground service is recorded however the run ended: a run
            // WorkManager stopped never reaches Run.end, and its hours count against the
            // day's allowance all the same. NonCancellable, because the finally of a cancelled
            // coroutine cannot suspend otherwise.
            withContext(NonCancellable) {
                foregroundRun(entryPoint.clock().nowMillis())?.let { run ->
                    entryPoint.foregroundBudgetStore().record(requestedUserId, run, run.endedAtMillis)
                }
            }
            runGuard.end()
        }
    }

    /** This run's time under the foreground service so far, ending at [nowMillis]; null when never promoted. */
    private fun foregroundRun(nowMillis: Long): ProtonForegroundRun? =
        foregroundStartedAtMillis?.let { startedAt ->
            ProtonForegroundRun(
                endedAtMillis = nowMillis,
                durationMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            )
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
        private val foregroundBudget = entryPoint.foregroundBudgetStore()
        private val clock = entryPoint.clock()

        /**
         * Enqueues the follow-up before returning, while this run is still the running job under
         * the unique name, so the scheduler chains it rather than dropping or cancelling it.
         * The time this run spent under the foreground service counts towards the budget the
         * follow-up is held back by, though it is recorded only in the worker's finally block,
         * which a cancelled run reaches too.
         */
        fun end(
            outcome: ProtonThumbnailWorkOutcome,
            progress: ProtonThumbnailWorkProgress,
            lastIdle: ProtonThumbnailQueueStep.Idle?,
        ): Result {
            val allowPreviews = previewsAllowed()
            val now = clock.nowMillis()
            val runs = foregroundBudget.runs(userId) + listOfNotNull(foregroundRun(now))
            val followUp =
                ProtonThumbnailFollowUpPolicy.followUp(
                    outcome,
                    workRemaining = ProtonThumbnailWorkPolicy.hasPendingWork(progress, allowPreviews),
                    previewsDeferred =
                        lastIdle?.previewsDeferred == true || (!allowPreviews && progress.previewsPending > 0),
                    retryAfterMillis = lastIdle?.retryAfterMillis,
                    networkWaitAttempt = if (processedBatch) 0 else networkWaitAttempt,
                    foregroundBudgetDelayMillis =
                        ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(runs, now),
                )
            if (followUp != null) followUps.enqueueFollowUp(userId, followUp)
            return finish(outcome)
        }
    }

    private var foregroundUnavailable = false

    /** When the promotion was refused, by the monotonic clock; null while the notification is up or untried. */
    private var foregroundUnavailableSinceMillis: Long? = null

    /** When the run first became a foreground service, by the monotonic clock. */
    private var foregroundStartedAtMillis: Long? = null
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
     * Android 12+ may refuse that while the app is in the background, and Android 15 refuses it
     * once the day's dataSync allowance is spent; rather than failing the run, it goes on
     * without a notification for [ProtonThumbnailForegroundBudgetPolicy.BACKGROUND_ONLY_RUN_MILLIS]
     * and then ends with a follow-up that waits (see the run loop).
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
            if (foregroundStartedAtMillis == null) foregroundStartedAtMillis = now
        } catch (error: IllegalStateException) {
            if (!ProtonThumbnailWorkPolicy.isForegroundStartRefusal(error)) throw error
            foregroundUnavailable = true
            foregroundUnavailableSinceMillis = now
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

        fun foregroundBudgetStore(): ProtonThumbnailForegroundBudgetStore

        fun clock(): LenswaveClock

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

    /** The promotion to a foreground service was refused; the run stopped before the platform would. */
    FOREGROUND_UNAVAILABLE("foreground-unavailable"),
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

    /**
     * Whether a run whose promotion was refused at [unavailableSinceMillis] has had its
     * [ProtonThumbnailForegroundBudgetPolicy.BACKGROUND_ONLY_RUN_MILLIS]; never while the
     * notification is up.
     */
    fun backgroundOnlyDeadlineReached(
        unavailableSinceMillis: Long?,
        nowMillis: Long,
    ): Boolean =
        unavailableSinceMillis != null &&
            nowMillis - unavailableSinceMillis >= ProtonThumbnailForegroundBudgetPolicy.BACKGROUND_ONLY_RUN_MILLIS

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
     * How many times in a row the viewer may be found busy before the run ends. Each busy step
     * is a few seconds of waiting plus the retry sleep under the foreground service; a viewer
     * fetching a large original kept that up until the run limit.
     */
    const val MAX_CONSECUTIVE_BUSY_STEPS = 3

    /** The follow-up's delay once the viewer stayed busy; long enough for a large original to finish. */
    const val FOREGROUND_BUSY_END_DELAY_MILLIS = 60_000L

    /**
     * The step a run takes while the viewer is downloading: the queues were not consulted, so
     * the work is still pending. The first few times it is worth asking again shortly; after
     * [MAX_CONSECUTIVE_BUSY_STEPS] the retry is too far off to sleep for, so the run ends and
     * the follow-up carries it as its initial delay.
     */
    fun foregroundBusyStep(consecutiveBusySteps: Int): ProtonThumbnailQueueStep.Idle {
        require(consecutiveBusySteps > 0) { "A busy step is at least the first one" }
        val retryAfterMillis =
            if (consecutiveBusySteps >= MAX_CONSECUTIVE_BUSY_STEPS) {
                FOREGROUND_BUSY_END_DELAY_MILLIS
            } else {
                FOREGROUND_BUSY_RETRY_MILLIS
            }
        return ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = retryAfterMillis)
    }

    /** The notification is re-posted at most every [PROGRESS_PUBLISH_INTERVAL_MILLIS] unless forced. */
    fun shouldPublishProgress(
        lastPublishedAtMillis: Long?,
        nowMillis: Long,
        force: Boolean,
    ): Boolean =
        force ||
            lastPublishedAtMillis == null ||
            nowMillis - lastPublishedAtMillis >= PROGRESS_PUBLISH_INTERVAL_MILLIS

    /** How long until the next progress publication is allowed; zero when it is allowed now. */
    fun progressPublishWaitMillis(
        lastPublishedAtMillis: Long?,
        nowMillis: Long,
    ): Long {
        if (lastPublishedAtMillis == null) return 0L
        return (lastPublishedAtMillis + PROGRESS_PUBLISH_INTERVAL_MILLIS - nowMillis)
            .coerceIn(0L, PROGRESS_PUBLISH_INTERVAL_MILLIS)
    }
}
