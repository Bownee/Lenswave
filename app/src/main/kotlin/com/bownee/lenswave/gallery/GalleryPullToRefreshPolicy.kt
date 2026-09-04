package com.bownee.lenswave.gallery

/**
 * Decides whether a touch may start a pull-to-refresh. Only the thumbnail area pulls: touches
 * that land on the pinned header, on the filter chips, or in a small gap below the chips scroll
 * those instead, so a pull that begins right at the chip border does not start a refresh.
 */
internal object GalleryPullToRefreshPolicy {
    /**
     * @param touchY the touch-down position in the refresh layout's coordinates
     * @param headerBottom the bottom edge of the pinned header, in the same coordinates
     * @param filterRow the vertical extent of the visible filter row, or null when it is hidden
     * @param gapBelowFilterRow extra pixels below the filter row that do not start a pull
     */
    fun startsPull(
        touchY: Float,
        headerBottom: Int,
        filterRow: IntRange?,
        gapBelowFilterRow: Int = 0,
    ): Boolean {
        if (touchY < headerBottom) return false
        if (filterRow == null) return true
        return touchY.toInt() !in filterRow.first..(filterRow.last + gapBelowFilterRow)
    }
}
