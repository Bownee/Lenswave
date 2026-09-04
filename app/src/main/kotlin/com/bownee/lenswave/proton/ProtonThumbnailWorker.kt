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
        val attempt = (runAttemptCount + 1).coerceAtMost(ProtonThumbnailWorkPolicy.MAX_ATTEMPTS)
        var statusPublished = false
        var networkMonitor: ProtonThumbnailNetworkMonitor? = null
        return try {
            val requestedUserId = UserId(userId)
            val session =
                withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
                    entryPoint.accountSessionManager().state.first { state ->
                        state.initialized && !state.transitioning
                    }
                } ?: return resolve(repository, ProtonThumbnailWorkIssue.TIMEOUT, publishStatus = false)
            if (session.activeUserId != requestedUserId) {
                return Result.failure()
            }
            val initialProgress = repository.thumbnailWorkProgress(requestedUserId)
            var previewsDeferred = false
            if (!ProtonThumbnailWorkPolicy.hasPendingWork(initialProgress, previewsAllowed())) {
                val previewsWaiting = initialProgress.previewsPending > 0
                if (previewsWaiting) entryPoint.thumbnailScheduler().enqueueWhileCharging(requestedUserId)
                return resolve(repository)
            }
            val monitor = ProtonThumbnailNetworkMonitor(applicationContext).also { networkMonitor = it }
            if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                return resolve(
                    repository,
                    ProtonThumbnailWorkIssue.INCOMPLETE,
                    publishStatus = false,
                )
            }
            val foregroundInfoFactory = ProtonThumbnailForegroundInfoFactory(applicationContext)
            publishForeground(foregroundInfoFactory, initialProgress.notificationProgress(), force = true)
            repository.updateThumbnailWorkStatus(
                ProtonThumbnailWorkStatus.Running(attempt, ProtonThumbnailWorkPolicy.MAX_ATTEMPTS),
            )
            statusPublished = true
            var completedWithinTime = false
            val issue =
                withTimeoutOrNull<ProtonThumbnailWorkIssue?>(
                    ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS,
                ) {
                    var sawFailure = false
                    var runIssue: ProtonThumbnailWorkIssue? = null
                    while (true) {
                        if (!monitor.awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                            completedWithinTime = true
                            runIssue = ProtonThumbnailWorkIssue.INCOMPLETE
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
                            ProtonThumbnailQueueStep.Downloaded -> {
                                Unit
                            }

                            ProtonThumbnailQueueStep.Failed -> {
                                sawFailure = true
                            }

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
                                completedWithinTime = true
                                runIssue =
                                    when {
                                        !step.hasPending -> null
                                        sawFailure -> ProtonThumbnailWorkIssue.ERROR
                                        else -> ProtonThumbnailWorkIssue.INCOMPLETE
                                    }
                                break
                            }
                        }
                    }
                    runIssue
                }
            publishForeground(
                foregroundInfoFactory,
                repository.thumbnailWorkProgress(requestedUserId).notificationProgress(),
                force = true,
            )
            if (previewsDeferred) entryPoint.thumbnailScheduler().enqueueWhileCharging(requestedUserId)
            resolve(
                repository,
                if (completedWithinTime) issue else ProtonThumbnailWorkIssue.TIMEOUT,
            )
        } catch (error: CancellationException) {
            if (statusPublished) {
                repository.updateThumbnailWorkStatus(null)
                reportState("interrupted")
            }
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.THUMBNAIL_WORKER, error)
            resolve(repository, ProtonThumbnailWorkIssue.ERROR, publishStatus = statusPublished)
        } finally {
            networkMonitor?.close()
        }
    }

    private var foregroundUnavailable = false
    private var lastForegroundPublishMillis: Long? = null

    /**
     * Previews are a gigabyte for a large library, so they wait for the charger unless the app is
     * on screen; thumbnails are small enough to download whenever unmetered network is available.
     */
    private fun previewsAllowed(): Boolean =
        ProtonThumbnailWorkPolicy.previewsAllowed(
            charging = applicationContext.getSystemService(BatteryManager::class.java)?.isCharging == true,
            appVisible =
                ActivityManager
                    .RunningAppProcessInfo()
                    .also(ActivityManager::getMyMemoryState)
                    .importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        )

    /**
     * Promotes the run to a foreground service so it can outlive the ten-minute background limit.
     * Android 12+ refuses that while the app is in the background, which is exactly when retries
     * fire; rather than failing the attempt, the run continues without a notification and lets
     * the platform stop it at the background limit, after which WorkManager reschedules it.
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

    private fun resolve(
        repository: ProtonThumbnailWorkGateway,
        issue: ProtonThumbnailWorkIssue? = null,
        publishStatus: Boolean = true,
    ): Result {
        val resolution = ProtonThumbnailWorkPolicy.resolve(runAttemptCount, issue)
        if (publishStatus) repository.updateThumbnailWorkStatus(resolution.status)
        reportState(resolution.diagnosticState)
        return when (resolution.decision) {
            ProtonThumbnailWorkDecision.SUCCESS -> Result.success()
            ProtonThumbnailWorkDecision.RETRY -> Result.retry()
            ProtonThumbnailWorkDecision.FAILURE -> Result.failure()
        }
    }

    private fun reportState(state: String) {
        LenswaveDiagnostics.reportState(
            operation = LenswaveOperation.THUMBNAIL_WORKER,
            state = state,
            attempt = (runAttemptCount + 1).coerceAtMost(ProtonThumbnailWorkPolicy.MAX_ATTEMPTS),
            maximumAttempts = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
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

internal enum class ProtonThumbnailWorkDecision {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal data class ProtonThumbnailWorkResolution(
    val decision: ProtonThumbnailWorkDecision,
    val status: ProtonThumbnailWorkStatus?,
    val diagnosticState: String,
)

internal object ProtonThumbnailWorkPolicy {
    const val MAX_ATTEMPTS = 25
    const val MAX_RUN_MILLIS = 5L * 60L * 60L * 1_000L + 30L * 60L * 1_000L
    const val PROGRESS_PUBLISH_INTERVAL_MILLIS = 1_500L

    /**
     * `ForegroundServiceStartNotAllowedException` (API 31+) extends IllegalStateException; it is
     * matched by name so the check compiles and tests run on the JVM.
     */
    fun isForegroundStartRefusal(error: Throwable): Boolean =
        error::class.java.simpleName == "ForegroundServiceStartNotAllowedException"

    /** Previews wait for the charger unless the app is on screen; thumbnails never wait. */
    fun previewsAllowed(
        charging: Boolean,
        appVisible: Boolean,
    ): Boolean = charging || appVisible

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

    fun resolve(
        runAttemptCount: Int,
        issue: ProtonThumbnailWorkIssue?,
    ): ProtonThumbnailWorkResolution {
        val attempt = (runAttemptCount + 1).coerceAtMost(MAX_ATTEMPTS)
        if (issue == null) {
            return ProtonThumbnailWorkResolution(
                ProtonThumbnailWorkDecision.SUCCESS,
                status = null,
                diagnosticState = "complete",
            )
        }
        if (attempt >= MAX_ATTEMPTS) {
            return ProtonThumbnailWorkResolution(
                ProtonThumbnailWorkDecision.FAILURE,
                ProtonThumbnailWorkStatus.Stopped(attempt, MAX_ATTEMPTS, issue),
                diagnosticState = "stopped-${issue.diagnosticName()}",
            )
        }
        return ProtonThumbnailWorkResolution(
            ProtonThumbnailWorkDecision.RETRY,
            retryStatus(runAttemptCount, issue),
            diagnosticState = "retry-${issue.diagnosticName()}",
        )
    }

    fun retryStatus(
        runAttemptCount: Int,
        issue: ProtonThumbnailWorkIssue,
    ): ProtonThumbnailWorkStatus.RetryScheduled =
        ProtonThumbnailWorkStatus.RetryScheduled(
            attempt = (runAttemptCount + 2).coerceAtMost(MAX_ATTEMPTS),
            maximumAttempts = MAX_ATTEMPTS,
            issue = issue,
        )

    private fun ProtonThumbnailWorkIssue.diagnosticName(): String = name.lowercase()
}
