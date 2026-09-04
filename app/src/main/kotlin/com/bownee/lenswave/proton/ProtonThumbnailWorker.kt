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
 * Every outcome except a crash ends the run successfully: the next app open, sync tick or
 * charging run enqueues the worker again, which costs far less than WorkManager retrying a run
 * that would only find nothing due yet.
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
        val repository = entryPoint.thumbnailWork()
        var networkMonitor: ProtonThumbnailNetworkMonitor? = null
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
            val initialProgress = repository.thumbnailWorkProgress(requestedUserId)
            if (!ProtonThumbnailWorkPolicy.hasPendingWork(initialProgress, previewsAllowed())) {
                if (initialProgress.previewsPending == 0) return finish(ProtonThumbnailWorkOutcome.COMPLETE)
                entryPoint.thumbnailScheduler().enqueueWhileCharging(requestedUserId)
                return finish(ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED)
            }
            val monitor = ProtonThumbnailNetworkMonitor(applicationContext).also { networkMonitor = it }
            if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                return finish(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK)
            }
            val foregroundInfoFactory = ProtonThumbnailForegroundInfoFactory(applicationContext)
            publishForeground(foregroundInfoFactory, initialProgress.notificationProgress(), force = true)
            var previewsDeferred = false
            val outcome =
                withTimeoutOrNull(ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS) {
                    var runOutcome: ProtonThumbnailWorkOutcome
                    while (true) {
                        if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                            runOutcome = ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK
                            break
                        }
                        when (
                            val step =
                                repository.downloadNextQueuedThumbnailBatch(
                                    requestedUserId,
                                    previewsAllowed(),
                                ) { progress ->
                                    publishForeground(foregroundInfoFactory, progress.notificationProgress())
                                }
                        ) {
                            ProtonThumbnailQueueStep.Processed -> {}

                            is ProtonThumbnailQueueStep.Idle -> {
                                val wait = ProtonBackgroundBatchPolicy.idleWaitMillis(step, MAX_IDLE_WAIT_MILLIS)
                                if (wait != null) {
                                    // A retry due within minutes is worth sleeping for: it keeps the
                                    // run alive instead of relying on a WorkManager retry that cannot
                                    // start from the background. Longer backoffs end the run.
                                    delay(wait + IDLE_WAIT_SLACK_MILLIS)
                                    continue
                                }
                                previewsDeferred = step.previewsDeferred
                                runOutcome =
                                    when {
                                        step.hasPending -> ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY
                                        previewsDeferred -> ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED
                                        else -> ProtonThumbnailWorkOutcome.COMPLETE
                                    }
                                break
                            }
                        }
                    }
                    runOutcome
                } ?: ProtonThumbnailWorkOutcome.TIMED_OUT
            publishForeground(
                foregroundInfoFactory,
                repository.thumbnailWorkProgress(requestedUserId).notificationProgress(),
                force = true,
            )
            if (previewsDeferred) entryPoint.thumbnailScheduler().enqueueWhileCharging(requestedUserId)
            finish(outcome)
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
            networkMonitor?.close()
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
            setForeground(factory.create(workerId = id, progress = progress))
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

        fun thumbnailScheduler(): ProtonThumbnailScheduler
    }

    companion object {
        const val KEY_USER_ID = "user-id"
        private const val SESSION_READY_TIMEOUT_MILLIS = 30_000L
        private const val NETWORK_READY_TIMEOUT_MILLIS = 5_000L

        /** Sleeping longer than this inside a foreground service would keep the phone awake for nothing. */
        private const val MAX_IDLE_WAIT_MILLIS = 2L * 60L * 1_000L
        private const val IDLE_WAIT_SLACK_MILLIS = 1_000L

        fun request(
            userId: UserId,
            requiresCharging: Boolean = false,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProtonThumbnailWorker>()
                .setInputData(workDataOf(KEY_USER_ID to userId.id))
                .setConstraints(
                    Constraints
                        .Builder()
                        // A network JobScheduler constraint can be revoked as the phone enters
                        // Doze, even after this worker has promoted itself to a foreground service.
                        // The worker checks for validated unmetered access between batches instead.
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiresCharging(requiresCharging)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
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
}

internal object ProtonThumbnailWorkPolicy {
    /** A run that crashes is retried a couple of times by WorkManager, then left to the next enqueue. */
    const val MAX_ERROR_ATTEMPTS = 3
    const val MAX_RUN_MILLIS = 5L * 60L * 60L * 1_000L + 30L * 60L * 1_000L
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
