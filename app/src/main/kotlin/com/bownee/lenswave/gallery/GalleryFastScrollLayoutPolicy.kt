package com.bownee.lenswave.gallery

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

internal data class GalleryFastScrollTarget(
    val position: Int,
    val positionOffsetFraction: Float,
)

internal object GalleryFastScrollLayoutPolicy {
    fun shouldShow(
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ): Boolean = canScrollBackward || canScrollForward

    fun handleTop(
        scrollOffset: Int,
        scrollRange: Int,
        viewportExtent: Int,
        trackStart: Int,
        trackEnd: Int,
        handleSize: Int,
    ): Int {
        val availableTravel = (trackEnd - trackStart - handleSize).coerceAtLeast(0)
        val scrollableRange = (scrollRange - viewportExtent).coerceAtLeast(0)
        if (scrollableRange == 0) return trackStart
        val progress = scrollOffset.toFloat().div(scrollableRange).coerceIn(0f, 1f)
        return trackStart + (availableTravel * progress).roundToInt()
    }

    fun dragProgress(
        pointerY: Float,
        pointerOffsetInHandle: Float,
        trackStart: Int,
        trackEnd: Int,
        handleSize: Int,
    ): Float {
        val availableTravel = (trackEnd - trackStart - handleSize).coerceAtLeast(0)
        if (availableTravel == 0) return 0f
        return ((pointerY - pointerOffsetInHandle - trackStart) / availableTravel).coerceIn(0f, 1f)
    }

    fun target(
        itemCount: Int,
        progress: Float,
    ): GalleryFastScrollTarget {
        if (itemCount <= 1) return GalleryFastScrollTarget(position = 0, positionOffsetFraction = 0f)
        val exactPosition = (itemCount - 1) * progress.coerceIn(0f, 1f)
        val position = floor(exactPosition).toInt().coerceIn(0, itemCount - 1)
        val offsetFraction = if (position == itemCount - 1) 0f else exactPosition - position
        return GalleryFastScrollTarget(position, offsetFraction)
    }

    /** Space below the last row so it clears the navigation bar and the selection bar when shown. */
    fun footerHeight(
        selectionBarVisible: Boolean,
        bottomInset: Int,
        selectionClearance: Int,
        baseClearance: Int,
    ): Int = max(if (selectionBarVisible) selectionClearance else baseClearance, 0) + bottomInset
}
