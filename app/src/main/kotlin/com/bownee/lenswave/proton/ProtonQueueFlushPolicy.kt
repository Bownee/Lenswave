package com.bownee.lenswave.proton

/**
 * When an in-memory download queue is written back to its encrypted file.
 *
 * Every settle used to rewrite the whole queue; over a backfill of thousands of thumbnails that
 * is quadratic in bytes and sits inside the SDK receive loop. Changes are now coalesced: a write
 * is scheduled a few seconds after the first unflushed change, brought forward once enough
 * changes pile up, and forced by the callers at the points where the process could lose it
 * (batch end, idle, worker stop).
 */
internal object ProtonQueueFlushPolicy {
    const val FLUSH_DELAY_MILLIS = 3_000L
    const val MAX_UNFLUSHED_CHANGES = 32

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
