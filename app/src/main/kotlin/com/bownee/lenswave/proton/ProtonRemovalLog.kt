package com.bownee.lenswave.proton

import java.util.TreeMap

/**
 * The photos removed per user since a given moment, kept against a monotonically increasing
 * epoch. A sync enumerates outside the lock that serializes commits, so its listing may still
 * name a photo trashed in the meantime; committing it whole would bring the photo back into
 * the file and onto the screen. The sync opens a snapshot before it reads anything, and at
 * commit time subtracts exactly the removals recorded after that snapshot, whatever is on
 * screen by then: a listing that could not be read for a moment, or an album that was closed,
 * no longer stands in for a removal log it never was.
 *
 * Bounded by construction: a removal is only kept while a snapshot that could ask for it is
 * open, and closing the oldest open snapshot drops everything older than the next one.
 */
internal class ProtonRemovalLog {
    private class Removal(
        val epoch: Long,
        val userId: String,
        val nodeUids: Set<String>,
    )

    private val lock = Any()
    private var epoch = 0L
    private val removals = ArrayDeque<Removal>()

    /** Epoch each open snapshot was taken at, with how many syncs share it. */
    private val openSnapshots = TreeMap<Long, Int>()

    /** Records that [nodeUids] left [userId]'s listings; call it under the lock that applied the removal. */
    fun record(
        userId: String,
        nodeUids: Collection<String>,
    ) {
        if (nodeUids.isEmpty()) return
        synchronized(lock) {
            epoch++
            // No sync is enumerating, so nothing will ever ask about this removal.
            if (openSnapshots.isEmpty()) return
            removals.addLast(Removal(epoch, userId, nodeUids.toSet()))
        }
    }

    /** The current epoch; removals recorded after it are answered by [removedSince] until [closeSnapshot]. */
    fun openSnapshot(): Long =
        synchronized(lock) {
            openSnapshots.merge(epoch, 1, Int::plus)
            epoch
        }

    fun closeSnapshot(snapshot: Long) {
        synchronized(lock) {
            val remaining = (openSnapshots[snapshot] ?: return) - 1
            if (remaining > 0) openSnapshots[snapshot] = remaining else openSnapshots.remove(snapshot)
            val oldestOpen = openSnapshots.firstEntry()?.key
            while (removals.isNotEmpty() && (oldestOpen == null || removals.first().epoch <= oldestOpen)) {
                removals.removeFirst()
            }
        }
    }

    /** The node uids removed from [userId]'s listings after [snapshot] was opened. */
    fun removedSince(
        userId: String,
        snapshot: Long,
    ): Set<String> =
        synchronized(lock) {
            val removed = HashSet<String>()
            removals.forEach { removal ->
                if (removal.epoch > snapshot && removal.userId == userId) removed.addAll(removal.nodeUids)
            }
            removed
        }

    /** [listing] without what [removedSince] names; the same instance when nothing was removed. */
    fun <T> retain(
        userId: String,
        snapshot: Long,
        listing: List<T>,
        nodeUid: (T) -> String,
    ): List<T> {
        val removed = removedSince(userId, snapshot)
        if (removed.isEmpty()) return listing
        return listing.filterNot { item -> nodeUid(item) in removed }
    }
}
