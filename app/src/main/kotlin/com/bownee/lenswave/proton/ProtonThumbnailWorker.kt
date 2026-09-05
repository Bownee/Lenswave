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
import me.proton.core.domain.entity.UserId
import java.util.concurrent.TimeUnit

/**
 * Drains the thumbnail and preview queues while a validated unmetered network is available.
 * The run itself is [ProtonThumbnailRun]; this class resolves its collaborators from Hilt,
 * gives it the Android answers it needs (the monotonic clock, the charger and the screen, the
 * network monitor, the foreground promotion) and maps its result onto WorkManager's.
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
        val requestedUserId = UserId(userId)
        // The monitor registers a system callback, so it exists only once a run gets as far
        // as needing the network; a paused or duplicate run never asks.
        val networkMonitor = lazy { ProtonThumbnailNetworkMonitor(applicationContext) }
        val foregroundInfoFactory = ProtonThumbnailForegroundInfoFactory(applicationContext, requestedUserId)
        val run =
            ProtonThumbnailRun(
                userId = requestedUserId,
                repository = entryPoint.thumbnailWork(),
                sessionState = entryPoint.accountSessionManager().state,
                followUps = entryPoint.followUpScheduler(),
                runGuard = entryPoint.runGuard(),
                transferCoordinator = entryPoint.transferCoordinator(),
                foregroundBudget = entryPoint.foregroundBudgetStore(),
                clock = entryPoint.clock(),
                pauseStore = entryPoint.pauseStore(),
                previewAdmission = entryPoint.previewAdmission(),
                input =
                    ProtonThumbnailRun.Input(
                        networkWaitAttempt = inputData.getInt(KEY_NETWORK_WAIT_ATTEMPT, 0),
                        replacedChargingRun = inputData.getBoolean(KEY_REPLACES_CHARGING_RUN, false),
                        runAttemptCount = runAttemptCount,
                    ),
                elapsedRealtimeMillis = SystemClock::elapsedRealtime,
                previewsAllowed = ::previewsAllowed,
                awaitValidatedUnmeteredNetwork = { timeoutMillis ->
                    networkMonitor.value.awaitValidatedUnmeteredNetwork(timeoutMillis)
                },
                setForeground = { progress -> setForeground(foregroundInfoFactory.create(progress)) },
                reportFailure = { error ->
                    LenswaveDiagnostics.reportFailure(LenswaveOperation.THUMBNAIL_WORKER, error)
                },
                reportState = ::reportState,
            )
        return try {
            when (run.execute()) {
                is ProtonThumbnailRunResult.Ended -> Result.success()
                ProtonThumbnailRunResult.Retry -> Result.retry()
                ProtonThumbnailRunResult.Failed -> Result.failure()
            }
        } finally {
            if (networkMonitor.isInitialized()) networkMonitor.value.close()
        }
    }

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
        const val KEY_REPLACES_CHARGING_RUN = "replaces-charging-run"

        fun request(
            userId: UserId,
            requiresCharging: Boolean = false,
            initialDelayMillis: Long = 0L,
            networkWaitAttempt: Int = 0,
            replacesChargingRun: Boolean = false,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProtonThumbnailWorker>()
                .setInputData(
                    workDataOf(
                        KEY_USER_ID to userId.id,
                        KEY_NETWORK_WAIT_ATTEMPT to networkWaitAttempt,
                        KEY_REPLACES_CHARGING_RUN to replacesChargingRun,
                    ),
                ).setConstraints(
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
