    fun idle(
        thumbnailsPending: Boolean,
        previewsPending: Boolean,
        thumbnailRetryDelayMillis: Long? = null,
        previewRetryDelayMillis: Long? = null,
    ): ProtonThumbnailQueueStep.Idle =
        ProtonThumbnailQueueStep.Idle(
            hasPending = thumbnailsPending || previewsPending,
            retryAfterMillis = listOfNotNull(thumbnailRetryDelayMillis, previewRetryDelayMillis).minOrNull(),
        )

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
    }package com.bownee.lenswave.proton

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
     * [claimPreviews], which is only invoked when no thumbnail is ready. Null means nothing is
     * ready right now.
     */
    suspend fun choose(
        thumbnailBatch: List<ProtonThumbnailQueueEntry>,
        claimPreviews: suspend () -> List<ProtonThumbnailQueueEntry>,
    ): ProtonBackgroundBatch? {
        if (thumbnailBatch.isNotEmpty()) return ProtonBackgroundBatch(ProtonQueueName.THUMBNAILS, thumbnailBatch)
        val previewBatch = claimPreviews()
        if (previewBatch.isNotEmpty()) return ProtonBackgroundBatch(ProtonQueueName.PREVIEWS, previewBatch)
        return null
    }

    /** Idle only reports "nothing left" when both queues are empty, so deferred retries keep the worker alive. */
    fun idle(
        thumbnailsPending: Boolean,
        previewsPending: Boolean,
    ): ProtonThumbnailQueueStep.Idle = ProtonThumbnailQueueStep.Idle(hasPending = thumbnailsPending || previewsPending)

    /** Failures that no retry can fix: Proton has no preview for this photo at all. */
    fun isPermanent(kind: ThumbnailFailureKind): Boolean = kind == ThumbnailFailureKind.NOT_FOUND
}
