package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId

/** How one worker run ended, as far as WorkManager is concerned. */
internal sealed interface ProtonThumbnailRunResult {
    /** The run ended on its own terms; every outcome is a successful WorkManager result. */
    data class Ended(
        val outcome: ProtonThumbnailWorkOutcome,
    ) : ProtonThumbnailRunResult

    /** The run crashed and has attempts left; WorkManager tries again after its backoff. */
    data object Retry : ProtonThumbnailRunResult

    /** The run was for another user's session, or crashed for the last time. */
    data object Failed : ProtonThumbnailRunResult
}

/**
 * One run of the thumbnail worker: the admission checks, the session and network waits, the
 * batch loop under the foreground notification, and the follow-up the outcome calls for.
 * Everything Android provides comes in through the constructor (the collaborators the worker
 * resolves from its Hilt entry point, and small function seams for the monotonic clock, the
 * foreground promotion, the previews-allowed answer and the network wait), so the loop runs
 * on the JVM under virtual time; [ProtonThumbnailWorker.doWork] is the wiring around it.
 *
 * Every outcome except a crash ends the run successfully: a WorkManager retry cannot start
 * from the background, so a run that leaves work behind enqueues its own follow-up request
 * instead ([ProtonThumbnailFollowUpPolicy]), under the same unique name and with the
 * constraints and delay that describe what it is waiting for.
 */
internal class ProtonThumbnailRun(
    private val userId: UserId,
    private val repository: ProtonThumbnailWorkGateway,
    private val sessionState: Flow<ProtonAccountSessionState>,
    private val followUps: ProtonThumbnailFollowUpScheduler,
    private val runGuard: ProtonThumbnailRunGuard,
    private val transferCoordinator: ProtonTransferCoordinator,
    private val foregroundBudget: ProtonThumbnailForegroundBudgetStore,
    private val clock: LenswaveClock,
    private val pauseStore: ProtonThumbnailPauseStore,
    private val previewAdmission: ProtonPreviewAdmission,
    private val input: Input,
    /** The monotonic clock, for deadlines and durations within the run. */
    private val elapsedRealtimeMillis: () -> Long,
    /** Whether previews may be downloaded right now; see [ProtonThumbnailWorkPolicy.previewsAllowed]. */
    private val previewsAllowed: () -> Boolean,
    /** True once a validated unmetered network is available, false when there is none after the timeout. */
    private val awaitValidatedUnmeteredNetwork: suspend (timeoutMillis: Long) -> Boolean,
    /** Promotes the run to a foreground service showing [ProtonThumbnailNotificationProgress]; may refuse. */
    private val setForeground: suspend (ProtonThumbnailNotificationProgress) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val reportState: (String) -> Unit,
) {
    /** What the work request carried into this run. */
    data class Input(
        /** The network backoff ladder this run was started on; see [ProtonThumbnailFollowUpPolicy]. */
        val networkWaitAttempt: Int = 0,
        /**
         * Whether this run took the place of a charging follow-up the scheduler found waiting; see
         * [ProtonThumbnailFollowUp.replacesChargingRun].
         */
        val replacedChargingRun: Boolean = false,
        /** WorkManager's count of earlier attempts at this request; decides whether a crash is retried. */
        val runAttemptCount: Int = 0,
    )

    /** A run that got a batch through resets the network backoff ladder. */
    private var processedBatch = false

    private var foregroundUnavailable = false

    /** When the promotion was refused, by the monotonic clock; null while the notification is up or untried. */
    private var foregroundUnavailableSinceMillis: Long? = null

    /** When the run first became a foreground service, by the monotonic clock. */
    private var foregroundStartedAtMillis: Long? = null
    private var lastForegroundPublishMillis: Long? = null

    suspend fun execute(): ProtonThumbnailRunResult {
        // The user paused downloads from the notification; a request that slipped past the
        // scheduler's check (one already queued when the pause was set) ends here.
        if (pauseStore.isPaused(userId)) {
            return endBeforeQueues(ProtonThumbnailWorkOutcome.PAUSED)
        }
        // Unique work de-duplicates per name and per WorkManager instance; this is the
        // process-wide guarantee that two batch loops never share the queues, and so that the
        // release of stale claims below the gateway only ever comes from the single active run.
        if (!runGuard.tryBegin()) {
            return endBeforeQueues(ProtonThumbnailWorkOutcome.ALREADY_RUNNING)
        }
        // The downloader asks between the chunks of a preview batch whether previews are still
        // allowed, so a batch a glance at the app authorised does not outlive the glance.
        previewAdmission.bind(previewsAllowed)
        return try {
            admitted()
        } catch (error: CancellationException) {
            reportState("interrupted")
            throw error
        } catch (error: Throwable) {
            reportFailure(error)
            if (ProtonThumbnailWorkPolicy.shouldRetryAfterError(input.runAttemptCount)) {
                reportState("retry-error")
                ProtonThumbnailRunResult.Retry
            } else {
                reportState("stopped-error")
                ProtonThumbnailRunResult.Failed
            }
        } finally {
            previewAdmission.unbind()
            // The time under the foreground service is recorded however the run ended: a run
            // WorkManager stopped never reaches end(), and its hours count against the day's
            // allowance all the same. NonCancellable, because the finally of a cancelled
            // coroutine cannot suspend otherwise.
            withContext(NonCancellable) {
                foregroundRun(clock.nowMillis())?.let { run ->
                    foregroundBudget.record(userId, run, run.endedAtMillis)
                }
            }
            runGuard.end()
        }
    }

    /** The run once it holds the guard: from the session wait to the follow-up. */
    private suspend fun admitted(): ProtonThumbnailRunResult {
        val session =
            withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
                sessionState.first { state -> state.initialized && !state.transitioning }
            } ?: return endBeforeQueues(ProtonThumbnailWorkOutcome.SESSION_UNAVAILABLE)
        if (session.activeUserId != userId) {
            return ProtonThumbnailRunResult.Failed
        }
        val initialProgress = repository.thumbnailWorkProgress(userId)
        if (!ProtonThumbnailWorkPolicy.hasPendingWork(initialProgress, previewsAllowed())) {
            val outcome =
                if (initialProgress.previewsPending == 0) {
                    ProtonThumbnailWorkOutcome.COMPLETE
                } else {
                    ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED
                }
            return end(outcome, initialProgress, lastIdle = null)
        }
        if (!awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
            return end(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, initialProgress, lastIdle = null)
        }
        var lastProgress = initialProgress
        publishForeground(lastProgress.notificationProgress(), force = true)
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
                            elapsedRealtimeMillis(),
                        )
                    ) {
                        runOutcome = ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE
                        break
                    }
                    if (!awaitValidatedUnmeteredNetwork(NETWORK_READY_TIMEOUT_MILLIS)) {
                        runOutcome = ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK
                        break
                    }
                    // Media the user opened comes first. The wait is short and the answer
                    // is an idle step, so the loop below decides what a busy viewer means
                    // for this run instead of the claim sitting on the viewer's download.
                    // A viewer that stays busy ends the run rather than keeping the
                    // foreground service awake to ask every few seconds.
                    val step =
                        if (!transferCoordinator.awaitNoForegroundTransfer(FOREGROUND_YIELD_TIMEOUT_MILLIS)) {
                            consecutiveBusySteps++
                            publishForeground(lastProgress.notificationProgress().copy(yielding = true))
                            ProtonThumbnailWorkPolicy.foregroundBusyStep(consecutiveBusySteps)
                        } else {
                            if (consecutiveBusySteps > 0) {
                                consecutiveBusySteps = 0
                                publishForeground(lastProgress.notificationProgress())
                            }
                            repository.downloadNextQueuedThumbnailBatch(userId, previewsAllowed()) { progress ->
                                lastProgress = progress
                                publishForeground(progress.notificationProgress())
                            }
                        }
                    when (step) {
                        ProtonThumbnailQueueStep.Processed -> {
                            processedBatch = true
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
        val finalProgress = repository.thumbnailWorkProgress(userId)
        publishForeground(finalProgress.notificationProgress(), force = true)
        return end(outcome, finalProgress, lastIdle)
    }

    /**
     * Ends a run that never reached the queues. The one follow-up such a run can owe is the
     * charging run it displaced ([Input.replacedChargingRun]): without it the previews that run
     * was for would wait for the next app open. The policy says no for a pause, and so does the
     * scheduler; the request is still enqueued from here so it chains after this run under the
     * unique name rather than being dropped by a KEEP.
     */
    private fun endBeforeQueues(outcome: ProtonThumbnailWorkOutcome): ProtonThumbnailRunResult {
        ProtonThumbnailFollowUpPolicy
            .followUp(
                outcome,
                workRemaining = false,
                previewsDeferred = false,
                retryAfterMillis = null,
                replacedChargingRun = input.replacedChargingRun,
            )?.let { followUp -> followUps.enqueueFollowUp(userId, followUp) }
        return finish(outcome)
    }

    /**
     * Enqueues the follow-up before returning, while this run is still the running job under
     * the unique name, so the scheduler chains it rather than dropping or cancelling it. The
     * time this run spent under the foreground service counts towards the budget the follow-up
     * is held back by, though it is recorded only in [execute]'s finally block, which a
     * cancelled run reaches too.
     */
    private fun end(
        outcome: ProtonThumbnailWorkOutcome,
        progress: ProtonThumbnailWorkProgress,
        lastIdle: ProtonThumbnailQueueStep.Idle?,
    ): ProtonThumbnailRunResult {
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
                networkWaitAttempt = if (processedBatch) 0 else input.networkWaitAttempt,
                foregroundBudgetDelayMillis =
                    ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(runs, now),
                replacedChargingRun = input.replacedChargingRun,
            )
        if (followUp != null) followUps.enqueueFollowUp(userId, followUp)
        return finish(outcome)
    }

    /** This run's time under the foreground service so far, ending at [nowMillis]; null when never promoted. */
    private fun foregroundRun(nowMillis: Long): ProtonForegroundRun? =
        foregroundStartedAtMillis?.let { startedAt ->
            ProtonForegroundRun(
                endedAtMillis = nowMillis,
                durationMillis = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L),
            )
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
        progress: ProtonThumbnailNotificationProgress,
        force: Boolean = false,
    ) {
        if (foregroundUnavailable) return
        val now = elapsedRealtimeMillis()
        if (!ProtonThumbnailWorkPolicy.shouldPublishProgress(lastForegroundPublishMillis, now, force)) return
        try {
            setForeground(progress)
            lastForegroundPublishMillis = now
            if (foregroundStartedAtMillis == null) foregroundStartedAtMillis = now
        } catch (error: IllegalStateException) {
            if (!ProtonThumbnailWorkPolicy.isForegroundStartRefusal(error)) throw error
            foregroundUnavailable = true
            foregroundUnavailableSinceMillis = now
            reportState("background-only")
        }
    }

    private fun finish(outcome: ProtonThumbnailWorkOutcome): ProtonThumbnailRunResult {
        reportState(outcome.diagnosticState)
        return ProtonThumbnailRunResult.Ended(outcome)
    }

    companion object {
        const val SESSION_READY_TIMEOUT_MILLIS = 30_000L
        const val NETWORK_READY_TIMEOUT_MILLIS = 5_000L
        const val FOREGROUND_YIELD_TIMEOUT_MILLIS = 5_000L
        const val IDLE_WAIT_SLACK_MILLIS = 1_000L
    }
}
