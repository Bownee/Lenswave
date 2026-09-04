package com.bownee.lenswave.proton

/** A request the worker queues for itself before a run ends, so that the work it leaves is picked up. */
internal data class ProtonThumbnailFollowUp(
    val requiresCharging: Boolean,
    val initialDelayMillis: Long = 0L,
) {
    init {
        require(initialDelayMillis >= 0L) { "A follow-up cannot be due in the past" }
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
     * [workRemaining] is whether either queue still has entries this kind of run may serve,
     * [previewsDeferred] whether previews are only waiting for the charger, and
     * [retryAfterMillis] the earliest backoff the last idle step reported, when any.
     */
    fun followUp(
        outcome: ProtonThumbnailWorkOutcome,
        workRemaining: Boolean,
        previewsDeferred: Boolean,
        retryAfterMillis: Long?,
    ): ProtonThumbnailFollowUp? =
        when (outcome) {
            ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK,
            ProtonThumbnailWorkOutcome.TIMED_OUT,
            -> {
                // The network constraint on the request is the wait; the run limit is a budget,
                // not a backoff. Either way the next run may start as soon as it is allowed to.
                if (workRemaining) ProtonThumbnailFollowUp(requiresCharging = false) else chargingRun(previewsDeferred)
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

            // Another run owns the queues, or the session is not there: the next app open, sync
            // tick or the other run enqueues again. A follow-up would only find the same thing.
            ProtonThumbnailWorkOutcome.SESSION_UNAVAILABLE,
            ProtonThumbnailWorkOutcome.ALREADY_RUNNING,
            -> {
                null
            }
        }

    private fun chargingRun(previewsDeferred: Boolean): ProtonThumbnailFollowUp? =
        if (previewsDeferred) ProtonThumbnailFollowUp(requiresCharging = true) else null
}
