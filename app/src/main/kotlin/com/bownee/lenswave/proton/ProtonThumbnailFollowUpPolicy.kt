package com.bownee.lenswave.proton

/**
 * A request the worker queues for itself before a run ends, so that the work it leaves is picked up.
 * [networkWaitAttempt] is how many runs in a row ended waiting for a validated network, carried
 * in the request's input data so the next run knows where the backoff ladder stands.
 */
internal data class ProtonThumbnailFollowUp(
    val requiresCharging: Boolean,
    val initialDelayMillis: Long = 0L,
    val networkWaitAttempt: Int = 0,
) {
    init {
        require(initialDelayMillis >= 0L) { "A follow-up cannot be due in the past" }
        require(networkWaitAttempt >= 0) { "A network wait attempt cannot be negative" }
    }
}

/**
 * Decides what a run enqueues about itself before it returns. A run ends successfully whatever
 * happened, because a WorkManager retry cannot start from the background; a follow-up request
 * under the same unique name, with an unmetered-network constraint and an initial delay where
 * a backoff is known, is what gets the remaining work done without anyone opening the app.
 */
internal object ProtonThumbnailFollowUpPolicy {
    /** A backoff shorter than this is not worth a separate job; the queue re-checks anyway. */
    const val MIN_RETRY_DELAY_MILLIS = 10_000L

    /** Bounded by the queues' own retry cap: nothing waits longer than that to become claimable. */
    const val MAX_RETRY_DELAY_MILLIS = 15L * 60L * 1_000L

    /**
     * The request's unmetered constraint is satisfied by a Wi-Fi that is connected but not
     * validated (a captive portal, a router without uplink): JobScheduler knows nothing about
     * validation, so the worker started, waited its few seconds for a validated network, and
     * was re-enqueued at once, over and over for as long as the Wi-Fi stayed like that. Runs
     * that end waiting for the network back off from here, doubling per consecutive attempt.
     */
    const val NETWORK_WAIT_BASE_DELAY_MILLIS = 60_000L
    const val MAX_NETWORK_WAIT_DELAY_MILLIS = 30L * 60L * 1_000L
    private const val MAX_NETWORK_WAIT_SHIFT = 5

    /**
     * A run that spent its whole budget is followed by a pause: back-to-back two-hour runs are
     * what exhausts the day's foreground allowance, and the pause also lets the phone doze.
     */
    const val RUN_LIMIT_DELAY_MILLIS = 60_000L

    /** How long the follow-up after the [attempt]th consecutive network wait is held back. */
    fun networkWaitDelayMillis(attempt: Int): Long {
        require(attempt >= 0) { "A network wait attempt cannot be negative" }
        val multiplier = 1L shl attempt.coerceAtMost(MAX_NETWORK_WAIT_SHIFT)
        return (NETWORK_WAIT_BASE_DELAY_MILLIS * multiplier).coerceAtMost(MAX_NETWORK_WAIT_DELAY_MILLIS)
    }

    /**
     * [workRemaining] is whether either queue still has entries this kind of run may serve,
     * [previewsDeferred] whether previews are only waiting for the charger,
     * [retryAfterMillis] the earliest backoff the last idle step reported, when any,
     * [networkWaitAttempt] how many runs in a row ended waiting for the network before this
     * one (zero after any run that processed a batch), and [foregroundBudgetDelayMillis] how
     * long the next run has to wait for the day's foreground allowance
     * ([ProtonThumbnailForegroundBudgetPolicy]); every follow-up waits at least that long.
     */
    fun followUp(
        outcome: ProtonThumbnailWorkOutcome,
        workRemaining: Boolean,
        previewsDeferred: Boolean,
        retryAfterMillis: Long?,
        networkWaitAttempt: Int = 0,
        foregroundBudgetDelayMillis: Long = 0L,
    ): ProtonThumbnailFollowUp? {
        require(foregroundBudgetDelayMillis >= 0L) { "A budget delay cannot be negative" }
        val followUp =
            followUpBeforeBudget(outcome, workRemaining, previewsDeferred, retryAfterMillis, networkWaitAttempt)
        return if (followUp == null || followUp.initialDelayMillis >= foregroundBudgetDelayMillis) {
            followUp
        } else {
            followUp.copy(initialDelayMillis = foregroundBudgetDelayMillis)
        }
    }

    private fun followUpBeforeBudget(
        outcome: ProtonThumbnailWorkOutcome,
        workRemaining: Boolean,
        previewsDeferred: Boolean,
        retryAfterMillis: Long?,
        networkWaitAttempt: Int,
    ): ProtonThumbnailFollowUp? =
        when (outcome) {
            ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK -> {
                if (workRemaining) {
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = networkWaitDelayMillis(networkWaitAttempt),
                        networkWaitAttempt = networkWaitAttempt + 1,
                    )
                } else {
                    chargingRun(previewsDeferred)
                }
            }

            ProtonThumbnailWorkOutcome.TIMED_OUT -> {
                if (workRemaining) {
                    ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = RUN_LIMIT_DELAY_MILLIS)
                } else {
                    chargingRun(previewsDeferred)
                }
            }

            ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE -> {
                if (workRemaining) {
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = ProtonThumbnailForegroundBudgetPolicy.FOREGROUND_REFUSED_DELAY_MILLIS,
                    )
                } else {
                    chargingRun(previewsDeferred)
                }
            }

            ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY -> {
                if (workRemaining) {
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis =
                            (retryAfterMillis ?: MIN_RETRY_DELAY_MILLIS)
                                .coerceIn(MIN_RETRY_DELAY_MILLIS, MAX_RETRY_DELAY_MILLIS),
                    )
                } else {
                    chargingRun(previewsDeferred)
                }
            }

            ProtonThumbnailWorkOutcome.COMPLETE,
            ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED,
            -> {
                chargingRun(previewsDeferred)
            }

            // Another run owns the queues, the session is not there, or the user said stop: the
            // next app open, sync tick, manual refresh or the other run enqueues again. A
            // follow-up would only find the same thing.
            ProtonThumbnailWorkOutcome.SESSION_UNAVAILABLE,
            ProtonThumbnailWorkOutcome.ALREADY_RUNNING,
            ProtonThumbnailWorkOutcome.PAUSED,
            -> {
                null
            }
        }

    private fun chargingRun(previewsDeferred: Boolean): ProtonThumbnailFollowUp? =
        if (previewsDeferred) ProtonThumbnailFollowUp(requiresCharging = true) else null
}
