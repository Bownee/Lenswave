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
     * deferred so the caller can schedule a charging run.
     */
    fun idle(
        thumbnailsPending: Boolean,
        previewsPending: Boolean,
        thumbnailRetryDelayMillis: Long? = null,
        previewRetryDelayMillis: Long? = null,
        allowPreviews: Boolean = true,
    ): ProtonThumbnailQueueStep.Idle =
        ProtonThumbnailQueueStep.Idle(
            hasPending = thumbnailsPending || (allowPreviews && previewsPending),
            retryAfterMillis =
                listOfNotNull(
                    thumbnailRetryDelayMillis,
                    previewRetryDelayMillis.takeIf { allowPreviews },
                ).minOrNull(),
            previewsDeferred = !allowPreviews && previewsPending,
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
     * How long an idle run should sleep before claiming again instead of ending and leaving the
     * restart to WorkManager, whose retries cannot start in the background. Null means end the run.
     */
    fun idleWaitMillis(
        idle: ProtonThumbnailQueueStep.Idle,
        maxWaitMillis: Long,
    ): Long? {
        if (!idle.hasPending) return null
        val delay = idle.retryAfterMillis ?: return null
        return delay.takeIf { it <= maxWaitMillis }
    }
}
