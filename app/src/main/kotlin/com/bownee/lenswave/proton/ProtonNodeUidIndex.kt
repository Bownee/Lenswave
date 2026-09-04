package com.bownee.lenswave.proton

/**
 * A node uid lookup over the most recent list instance it was asked about.
 *
 * Repository state lists are immutable snapshots that change a few times per download batch,
 * while stale-thumbnail invalidations arrive per grid cell, so building the map once per
 * snapshot and answering from it beats scanning the list on every question. The memo maps a
 * uid to its position, so answers always come from the list asked about, never from a stale
 * snapshot.
 *
 * An invalidation itself publishes a new list instance (a thumbnail flag flipped on one item),
 * so a run of corrupt thumbnails would rebuild the map per cell. A new instance that carries
 * the same uids in the same positions therefore keeps the memo: that check is one pass of
 * reference comparisons, no hashing and no allocation, where a rebuild hashes every uid into a
 * fresh map. Concurrent callers may build or check twice, harmlessly.
 */
internal class ProtonNodeUidIndex<T>(
    private val nodeUid: (T) -> String?,
) {
    @Volatile
    private var memo: Memo<T>? = null

    /** The first item of [items] carrying [uid], or null. */
    fun find(
        items: List<T>,
        uid: String,
    ): T? = positions(items)[uid]?.let(items::get)

    fun contains(
        items: List<T>,
        uid: String,
    ): Boolean = uid in positions(items)

    /** The position of the first item of [items] carrying [uid], or null. */
    fun position(
        items: List<T>,
        uid: String,
    ): Int? = positions(items)[uid]

    private fun positions(items: List<T>): Map<String, Int> {
        val cached = memo
        if (cached != null) {
            if (cached.items === items) return cached.positionByNodeUid
            if (hasSameNodeUids(cached.items, items)) {
                memo = Memo(items, cached.positionByNodeUid)
                return cached.positionByNodeUid
            }
        }
        val positionByNodeUid = HashMap<String, Int>(items.size * 4 / 3 + 1)
        items.forEachIndexed { position, item ->
            nodeUid(item)?.let { uid -> positionByNodeUid.putIfAbsent(uid, position) }
        }
        memo = Memo(items, positionByNodeUid)
        return positionByNodeUid
    }

    private fun hasSameNodeUids(
        previous: List<T>,
        current: List<T>,
    ): Boolean {
        if (previous.size != current.size) return false
        for (position in current.indices) {
            if (nodeUid(previous[position]) != nodeUid(current[position])) return false
        }
        return true
    }

    private class Memo<T>(
        val items: List<T>,
        val positionByNodeUid: Map<String, Int>,
    )
}
