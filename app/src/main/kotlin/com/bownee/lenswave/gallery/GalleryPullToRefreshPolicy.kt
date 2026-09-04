package com.bownee.lenswave.gallery

/**
 * Decides whether a touch may start a pull-to-refresh. Only the thumbnail area pulls: touches
 * that land on the pinned header or on the filter chips scroll those instead.
 */
internal object GalleryPullToRefreshPolicy {
    /**
     * @param touchY the touch-down position in the refresh layout's coordinates
     * @param headerBottom the bottom edge of the pinned header, in the same coordinates
     * @param filterRow the vertical extent of the visible filter row, or null when it is hidden
     */
    fun startsPull(
        touchY: Float,
        headerBottom: Int,
        filterRow: IntRange?,
    ): Boolean {
        if (touchY < headerBottom) return false
        return filterRow == null || touchY.toInt() !in filterRow
    }
}
