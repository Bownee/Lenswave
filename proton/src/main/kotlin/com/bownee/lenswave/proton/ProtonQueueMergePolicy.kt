package com.bownee.lenswave.proton

/**
 * How an in-memory download queue that hydrated as empty, because its file could not be read at
 * the time, is written back once the file can be read again: memory wins for every node it
 * knows, and the stored entries it never saw are kept rather than dropped.
 */
internal object ProtonQueueMergePolicy {
    fun merge(
        inMemory: List<ProtonThumbnailQueueEntry>,
        stored: List<ProtonThumbnailQueueEntry>,
    ): List<ProtonThumbnailQueueEntry> {
        if (stored.isEmpty()) return inMemory
        val known = inMemory.mapTo(HashSet(inMemory.size * 4 / 3 + 1), ProtonThumbnailQueueEntry::nodeUid)
        return inMemory + stored.filterNot { entry -> entry.nodeUid in known }
    }
}
