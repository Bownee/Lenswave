package com.bownee.lenswave.proton

/**
 * A node uid lookup over the most recent list instance it was asked about.
 *
 * Repository state lists are immutable snapshots that change a few times per download batch,
 * while stale-thumbnail invalidations arrive per grid cell, so building the map once per
 * snapshot and answering from it beats scanning the list on every question. A different list
 * instance simply replaces the memo; concurrent callers may build it twice, harmlessly.
 */
internal class ProtonNodeUidIndex<T>(
    private val nodeUid: (T) -> String?,
) {
    @Volatile
    private var memo: Memo<T>? = null

    fun of(items: List<T>): Map<String, T> {
        memo?.let { cached -> if (cached.items === items) return cached.byNodeUid }
        val byNodeUid = HashMap<String, T>(items.size * 4 / 3 + 1)
        items.forEach { item -> nodeUid(item)?.let { uid -> byNodeUid.putIfAbsent(uid, item) } }
        memo = Memo(items, byNodeUid)
        return byNodeUid
    }

    private class Memo<T>(
        val items: List<T>,
        val byNodeUid: Map<String, T>,
    )
}
