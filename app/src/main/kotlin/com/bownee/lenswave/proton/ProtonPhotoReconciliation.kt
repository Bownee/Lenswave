package com.bownee.lenswave.proton

internal data class ProtonPhotoChanges(
    /** Cached photos the remote listing no longer names. */
    val removedNodeUids: Set<String> = emptySet(),
    /** Whether the remote listing names anything the cache did not. */
    val hasAdditions: Boolean = false,
) {
    val isEmpty: Boolean get() = removedNodeUids.isEmpty() && !hasAdditions
}

internal object ProtonPhotoReconciliation {
    /** Only what a reconcile acts on: the removals it deletes for, and whether anything at all changed. */
    fun compare(
        cachedNodeUids: Collection<String>,
        remoteNodeUids: Set<String>,
    ): ProtonPhotoChanges {
        val cached = cachedNodeUids.toHashSet()
        val removed = cached.filterNotTo(HashSet()) { nodeUid -> nodeUid in remoteNodeUids }
        val retainedCount = cached.size - removed.size
        return ProtonPhotoChanges(
            removedNodeUids = removed,
            hasAdditions = remoteNodeUids.size > retainedCount,
        )
    }

    /**
     * [enumerated] without the photos that were removed while the enumeration ran: those that
     * [existing] (the listing when the enumeration started) had and [published] (the listing at
     * commit time) no longer has. A listing enumerated outside the lock that serializes commits
     * may still name a photo trashed in the meantime; committing it whole would bring the photo
     * back into the file and onto the screen. Photos the enumeration added are kept: they are
     * new remotely, not removed locally. A null [published] cannot judge and keeps the listing
     * whole; so does one nothing was removed from, which returns the same instance.
     */
    fun <T> withoutRemovedSince(
        enumerated: List<T>,
        existing: List<T>,
        published: List<T>?,
        nodeUid: (T) -> String,
    ): List<T> {
        if (published == null || published === existing) return enumerated
        val publishedNodeUids = published.mapTo(HashSet(published.size * 4 / 3 + 1), nodeUid)
        val removedNodeUids =
            existing.mapNotNullTo(HashSet()) { item -> nodeUid(item).takeIf { it !in publishedNodeUids } }
        if (removedNodeUids.isEmpty()) return enumerated
        return enumerated.filterNot { item -> nodeUid(item) in removedNodeUids }
    }
}
