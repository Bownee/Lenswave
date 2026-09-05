package com.bownee.lenswave.proton

/** One claimed batch of the background worker together with the queue it came from. */
internal data class ProtonBackgroundBatch(
    val queue: ProtonQueueName,
    val entries: List<ProtonThumbnailQueueEntry>,
)

/**
 * Decides which queue the background worker serves next. Thumbnails always come first: previews
 * are only claimed when no thumbnail is ready, and the worker is idle only when both queues are
 * empty.
 */
internal object ProtonBackgroundBatchPolicy {
    /**
     * Returns the thumbnail batch when it has entries; otherwise claims previews through
     * [claimPreviews], which is only invoked when no thumbnail is ready and [allowPreviews] holds.
     * Null means nothing is ready right now.
     */
    suspend fun choose(
        thumbnailBatch: List<ProtonThumbnailQueueEntry>,
        allowPreviews: Boolean = true,
        claimPreviews: suspend () -> List<ProtonThumbnailQueueEntry>,
    ): ProtonBackgroundBatch? {
        if (thumbnailBatch.isNotEmpty()) return ProtonBackgroundBatch(ProtonQueueName.THUMBNAILS, thumbnailBatch)
        if (!allowPreviews) return null
        val previewBatch = claimPreviews()
        if (previewBatch.isNotEmpty()) return ProtonBackgroundBatch(ProtonQueueName.PREVIEWS, previewBatch)
        return null
    }

    /**
     * Idle only reports "nothing left" when both queues are empty, so deferred retries keep the
     * worker alive; [ProtonThumbnailQueueStep.Idle.retryAfterMillis] says how soon the earliest
     * backed-off entry across both queues can be claimed again. Previews that wait for the
     * charger ([allowPreviews] false) are not pending work for this run but are flagged as
     * deferred so the caller can schedule a charging run; so are thumbnails that can only be
     * had as previews ([thumbnailsAwaitingPreviews]), which the thumbnail queue's pending
     * state already leaves out while previews are not allowed.
     */
    fun idle(
        thumbnailsPending: Boolean,
        previewsPending: Boolean,
        thumbnailRetryDelayMillis: Long? = null,
        previewRetryDelayMillis: Long? = null,
        allowPreviews: Boolean = true,
        thumbnailsAwaitingPreviews: Boolean = false,
    ): ProtonThumbnailQueueStep.Idle =
        ProtonThumbnailQueueStep.Idle(
            hasPending = thumbnailsPending || (allowPreviews && previewsPending),
            retryAfterMillis =
                listOfNotNull(
                    thumbnailRetryDelayMillis,
                    previewRetryDelayMillis.takeIf { allowPreviews },
                ).minOrNull(),
            previewsDeferred = !allowPreviews && (previewsPending || thumbnailsAwaitingPreviews),
        )

    /**
     * Pending work whose earliest retry is due now and that still could not be claimed can only
     * be held by claims nobody settled or released; the background sync is the sole claimer.
     * That holds because [ProtonThumbnailRunGuard] admits one worker run per process: only the
     * active run reaches this decision, so releasing every claim never takes a batch away from
     * another run.
     */
    fun hasStaleClaims(idle: ProtonThumbnailQueueStep.Idle): Boolean = idle.hasPending && idle.retryAfterMillis == 0L

    /**
     * The step after a batch the SDK never answered ([ThumbnailBatchResult.stalled]). Its nodes
     * were settled as connection failures and are claimable again in
     * [ProtonThumbnailQueue.NETWORK_RETRY_MILLIS]; other entries may be ready right now, but
     * asking a stalled SDK for them would cost another deadline each with nothing stored, so the
     * retry is reported as at least that long. That is longer than [MAX_IDLE_WAIT_MILLIS], so the
     * run ends waiting for the retry and the follow-up carries the wait, with nothing held awake.
     */
    fun afterStalledBatch(idle: ProtonThumbnailQueueStep.Idle): ProtonThumbnailQueueStep.Idle =
        if (idle.hasPending) {
            idle.copy(retryAfterMillis = maxOf(idle.retryAfterMillis ?: 0L, ProtonThumbnailQueue.NETWORK_RETRY_MILLIS))
        } else {
            idle
        }

    /**
     * The longest an idle run sleeps in place. A run is a foreground service holding a wakelock,
     * so a sleep of minutes (the earlier cap) kept the phone awake for nothing, and a failing
     * tail of retries kept it awake for as long as the run limit allowed. A retry due within a
     * few seconds is still worth a sleep, because ending and starting a job costs more than
     * that; anything longer ends the run, and the follow-up request carries the backoff as its
     * initial delay ([ProtonThumbnailFollowUpPolicy]) while nothing is held awake.
     */
    const val MAX_IDLE_WAIT_MILLIS = 5_000L

    /** How long an idle run should sleep before claiming again; null means end the run. */
    fun idleWaitMillis(
        idle: ProtonThumbnailQueueStep.Idle,
        maxWaitMillis: Long = MAX_IDLE_WAIT_MILLIS,
    ): Long? {
        if (!idle.hasPending) return null
        val delay = idle.retryAfterMillis ?: return null
        return delay.takeIf { it <= maxWaitMillis }
    }
}
