package com.bownee.lenswave.gallery

/**
 * The pinned header (navigation and filter chips) slides out of the way as the list scrolls
 * down and back in as soon as it scrolls up, wherever the list is; at the very top it is
 * always fully shown. [hidden] is how many pixels of it are scrolled out, 0 to the header's
 * height.
 */
internal object GalleryHeaderScrollPolicy {
    fun hiddenAfter(
        hidden: Int,
        scrolledDown: Int,
        headerHeight: Int,
        atTop: Boolean,
    ): Int = if (atTop) 0 else (hidden + scrolledDown).coerceIn(0, headerHeight)

    /** Where a header left partway settles once the list stops: back in unless most of it is out. */
    fun settled(
        hidden: Int,
        headerHeight: Int,
    ): Int = if (hidden * 2 < headerHeight) 0 else headerHeight
}

/**
 * Turns a list's scroll callbacks into pixel deltas. A ListView reports no offset of its own
 * (its scroll range is estimated from row counts), so the delta comes from watching the row
 * that was at the top last time: while it is still laid out, its top edge has moved by exactly
 * the scrolled distance; once it has left the screen the list has moved by more than a
 * header's worth, and [FAR] says so in the right direction.
 */
internal class GalleryListScrollTracker {
    private var lastPosition = NONE
    private var lastTop = 0

    /**
     * The pixels the content moved up (positive) or down since the last call, given the first
     * laid-out row's position and the top edge of each laid-out child.
     */
    fun scrolled(
        firstVisiblePosition: Int,
        childCount: Int,
        childTop: (index: Int) -> Int,
    ): Int {
        if (childCount == 0) return 0
        val delta =
            when {
                lastPosition == NONE -> {
                    0
                }

                lastPosition in firstVisiblePosition until firstVisiblePosition + childCount -> {
                    lastTop - childTop(lastPosition - firstVisiblePosition)
                }

                firstVisiblePosition > lastPosition -> {
                    FAR
                }

                else -> {
                    -FAR
                }
            }
        lastPosition = firstVisiblePosition
        lastTop = childTop(0)
        return delta
    }

    /** Forgets the reference row; the next callback reports no movement. Call when the rows change. */
    fun reset() {
        lastPosition = NONE
    }

    companion object {
        const val FAR = 1_000_000
        private const val NONE = -1
    }
}
