package com.bownee.lenswave.viewer

import kotlin.math.max
import kotlin.math.min

/**
 * Which slice of the gallery list the viewer keeps as its navigation window. The intent carries
 * only a small window around the opened photo; while the user swipes towards an edge the window
 * grows from the in-process list well before the edge is reached, so a swipe never finds itself
 * without a neighbour that the gallery would have had.
 */
internal object PhotoNavigationWindowPolicy {
    /** Entries kept either side of the opened photo. */
    const val RADIUS = 20

    /** Grow once this few entries remain between the current photo and the window's edge. */
    const val EXTENSION_MARGIN = 5

    /** Entries added per growth step, so a long swipe run grows in a few strides rather than many. */
    const val EXTENSION_STEP = 20

    /** Absolute indices into the gallery list: [start] inclusive, [end] exclusive. */
    data class Window(
        val start: Int,
        val end: Int,
    ) {
        val size: Int get() = end - start
    }

    fun initial(
        currentIndex: Int,
        listSize: Int,
    ): Window {
        if (listSize <= 0) return Window(0, 0)
        val index = currentIndex.coerceIn(0, listSize - 1)
        return Window(max(0, index - RADIUS), min(listSize, index + RADIUS + 1))
    }

    /**
     * The grown [window] when [currentIndex] has come within [EXTENSION_MARGIN] of an edge that
     * still has list beyond it; null when the window is fine as it is.
     */
    fun extended(
        window: Window,
        currentIndex: Int,
        listSize: Int,
    ): Window? {
        if (currentIndex !in window.start until window.end) return null
        val nearStart = window.start > 0 && currentIndex - window.start <= EXTENSION_MARGIN
        val nearEnd = window.end < listSize && window.end - 1 - currentIndex <= EXTENSION_MARGIN
        if (!nearStart && !nearEnd) return null
        return Window(
            start = if (nearStart) max(0, window.start - EXTENSION_STEP) else window.start,
            end = if (nearEnd) min(listSize, window.end + EXTENSION_STEP) else window.end,
        )
    }
}
