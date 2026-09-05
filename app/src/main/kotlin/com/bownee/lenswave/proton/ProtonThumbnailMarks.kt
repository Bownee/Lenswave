package com.bownee.lenswave.proton

/**
 * Flips the thumbnail availability of every item whose node uid is in [nodeUids].
 *
 * Marks arrive per download batch over lists that hold a whole library, so the list is not
 * mapped: [index] answers where each marked uid sits, the list is copied once, and only those
 * positions are replaced; every other item keeps its instance. Returns null when no item
 * changed, so callers can leave their state untouched and avoid publishing an identical snapshot.
 */
internal fun <T> List<T>.withThumbnailAvailability(
    nodeUids: Collection<String>,
    available: Boolean,
    index: ProtonNodeUidIndex<T>,
    hasThumbnail: (T) -> Boolean,
    copy: (T, Boolean) -> T,
): List<T>? {
    if (nodeUids.isEmpty() || isEmpty()) return null
    var updated: MutableList<T>? = null
    nodeUids.forEach { nodeUid ->
        val position = index.position(this, nodeUid) ?: return@forEach
        val item = this[position]
        if (hasThumbnail(item) == available) return@forEach
        val target = updated ?: ArrayList(this).also { updated = it }
        target[position] = copy(item, available)
    }
    return updated
}

/** Whether any of [nodeUids] is in this list at all; a mark for none of them can skip its state update. */
internal fun <T> List<T>.containsAnyNodeUid(
    nodeUids: Collection<String>,
    index: ProtonNodeUidIndex<T>,
): Boolean = isNotEmpty() && nodeUids.any { nodeUid -> index.contains(this, nodeUid) }
