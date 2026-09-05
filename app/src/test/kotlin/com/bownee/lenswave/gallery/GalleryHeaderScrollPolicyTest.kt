package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryHeaderScrollPolicyTest {
    @Test
    fun `scrolling down hides the header up to its height and scrolling up brings it back`() {
        assertEquals(
            30,
            GalleryHeaderScrollPolicy.hiddenAfter(hidden = 0, scrolledDown = 30, headerHeight = 100, atTop = false),
        )
        assertEquals(
            100,
            GalleryHeaderScrollPolicy.hiddenAfter(hidden = 90, scrolledDown = 500, headerHeight = 100, atTop = false),
        )
        assertEquals(
            60,
            GalleryHeaderScrollPolicy.hiddenAfter(hidden = 100, scrolledDown = -40, headerHeight = 100, atTop = false),
        )
        assertEquals(
            0,
            GalleryHeaderScrollPolicy.hiddenAfter(hidden = 10, scrolledDown = -500, headerHeight = 100, atTop = false),
        )
    }

    @Test
    fun `at the top of the list the header is always fully shown`() {
        assertEquals(
            0,
            GalleryHeaderScrollPolicy.hiddenAfter(hidden = 100, scrolledDown = 20, headerHeight = 100, atTop = true),
        )
    }

    @Test
    fun `a header left partway settles in unless most of it is out`() {
        assertEquals(0, GalleryHeaderScrollPolicy.settled(hidden = 49, headerHeight = 100))
        assertEquals(100, GalleryHeaderScrollPolicy.settled(hidden = 50, headerHeight = 100))
        assertEquals(0, GalleryHeaderScrollPolicy.settled(hidden = 0, headerHeight = 100))
    }

    @Test
    fun `the tracker reports the distance the reference row moved while it stays laid out`() {
        val tracker = GalleryListScrollTracker()
        val tops = intArrayOf(120, 420, 720)

        assertEquals(0, tracker.scrolled(firstVisiblePosition = 0, childCount = 3) { tops[it] })
        // Content moved up by 50: every top is 50 less.
        assertEquals(50, tracker.scrolled(firstVisiblePosition = 0, childCount = 3) { tops[it] - 50 })
        // Another 70 up: the reference row is still laid out, now at 0.
        assertEquals(70, tracker.scrolled(firstVisiblePosition = 0, childCount = 3) { intArrayOf(0, 300, 600)[it] })
        // Back down by 20.
        assertEquals(-20, tracker.scrolled(firstVisiblePosition = 0, childCount = 3) { intArrayOf(20, 320, 620)[it] })
    }

    @Test
    fun `a jump past the laid-out rows reads as a far scroll in its direction`() {
        val tracker = GalleryListScrollTracker()
        tracker.scrolled(firstVisiblePosition = 0, childCount = 2) { 100 * it }

        assertEquals(
            GalleryListScrollTracker.FAR,
            tracker.scrolled(firstVisiblePosition = 40, childCount = 2) {
                100 *
                    it
            },
        )
        assertEquals(
            -GalleryListScrollTracker.FAR,
            tracker.scrolled(firstVisiblePosition = 3, childCount = 2) {
                100 *
                    it
            },
        )
    }

    @Test
    fun `a reset forgets the reference row and an empty list moves nothing`() {
        val tracker = GalleryListScrollTracker()
        tracker.scrolled(firstVisiblePosition = 5, childCount = 2) { 100 * it }
        tracker.reset()

        assertEquals(0, tracker.scrolled(firstVisiblePosition = 9, childCount = 2) { 100 * it })
        assertEquals(0, tracker.scrolled(firstVisiblePosition = 9, childCount = 0) { 100 * it })
    }
}
