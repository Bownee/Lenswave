package com.bownee.lenswave.proton

/**
 * When an in-memory download queue is written back to its encrypted file.
 *
 * Every settle used to rewrite the whole queue; over a backfill of thousands of thumbnails that
 * is quadratic in bytes and sits inside the SDK receive loop. Changes are now coalesced: a write
 * is scheduled a few seconds after the first unflushed change, brought forward once enough
 * changes pile up, and forced by the callers only where the process could otherwise lose it:
 * a stopped worker, the run deadline, an idle run, and every [BATCHES_PER_FORCED_FLUSH]th batch.
 * Forcing it after every batch would put the quadratic rewrite straight back.
 */
internal object ProtonQueueFlushPolicy {
    const val FLUSH_DELAY_MILLIS = 3_000L
    const val MAX_UNFLUSHED_CHANGES = 32

    /** A forced write every few batches bounds what a killed process downloads again. */
    const val BATCHES_PER_FORCED_FLUSH = 8

    /** A disk that keeps refusing the write is asked again less and less often. */
    const val MAX_WRITE_RETRY_DELAY_MILLIS = 5L * 60L * 1_000L
    private const val MAX_WRITE_RETRY_SHIFT = 7

    /**
     * How long to wait before the next write, given [unflushedChanges] since the last one, or null
     * when a write is already scheduled and can absorb this change. Zero means write at once.
     */
    fun flushDelayMillis(
        unflushedChanges: Int,
        flushScheduled: Boolean,
    ): Long? {
        if (unflushedChanges <= 0) return null
        if (unflushedChanges >= MAX_UNFLUSHED_CHANGES) return 0L
        return if (flushScheduled) null else FLUSH_DELAY_MILLIS
    }

    fun shouldFlushAfterBatch(batchesSinceForcedFlush: Int): Boolean =
        batchesSinceForcedFlush >= BATCHES_PER_FORCED_FLUSH

    /**
     * A write that threw leaves the queue dirty; without a retry nothing would write it until the
     * next change. Retries back off from [FLUSH_DELAY_MILLIS] up to [MAX_WRITE_RETRY_DELAY_MILLIS].
     */
    fun retryDelayAfterFailedWrite(consecutiveFailures: Int): Long {
        require(consecutiveFailures > 0) { "A retry needs at least one failed write" }
        val multiplier = 1L shl (consecutiveFailures - 1).coerceAtMost(MAX_WRITE_RETRY_SHIFT)
        return (FLUSH_DELAY_MILLIS * multiplier).coerceAtMost(MAX_WRITE_RETRY_DELAY_MILLIS)
    }

    /** One report per streak of failed writes; a retry loop must not flood the log. */
    fun shouldReportWriteFailure(consecutiveFailures: Int): Boolean = consecutiveFailures == 1

    /** A snapshot older than what is already on disk must not overwrite the newer file. */
    fun isStale(
        snapshotGeneration: Long,
        writtenGeneration: Long,
    ): Boolean = snapshotGeneration <= writtenGeneration
}

/** Bounded top-k selection so claiming a handful of entries never sorts the whole queue. */
internal object ProtonQueueSelectionPolicy {
    fun <T> takeFirst(
        candidates: Iterable<T>,
        limit: Int,
        order: Comparator<in T>,
    ): List<T> {
        require(limit > 0) { "The selection limit must be positive" }
        // The heap keeps the worst retained candidate on top so it can be replaced in O(log k).
        val retained = java.util.PriorityQueue<T>(limit + 1, order.reversed())
        candidates.forEach { candidate ->
            if (retained.size < limit) {
                retained.add(candidate)
            } else if (order.compare(candidate, retained.peek()) < 0) {
                retained.poll()
                retained.add(candidate)
            }
        }
        return retained.sortedWith(order)
    }
}
