package com.bownee.lenswave.proton

/**
 * Flips the thumbnail availability of every item whose node uid is in [nodeUids].
 *
 * Returns null when no item changed, so callers can leave their state untouched and avoid
 * publishing an identical snapshot.
 */
internal fun <T> List<T>.withThumbnailAvailability(
    nodeUids: Collection<String>,
    available: Boolean,
    nodeUid: (T) -> String?,
    hasThumbnail: (T) -> Boolean,
    copy: (T, Boolean) -> T,
): List<T>? {
    if (nodeUids.isEmpty()) return null
    var changed = false
    val updated = map { item ->
        if (nodeUid(item) !in nodeUids || hasThumbnail(item) == available) return@map item
        changed = true
        copy(item, available)
    }
    return if (changed) updated else null
}
