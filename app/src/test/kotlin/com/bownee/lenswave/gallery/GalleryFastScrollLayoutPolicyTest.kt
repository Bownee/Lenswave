package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFastScrollLayoutPolicyTest {
    @Test
    fun `handle is shown only when the list can move`() {
        assertFalse(GalleryFastScrollLayoutPolicy.shouldShow(false, false))
        assertTrue(GalleryFastScrollLayoutPolicy.shouldShow(false, true))
        assertTrue(GalleryFastScrollLayoutPolicy.shouldShow(true, false))
    }

    @Test
    fun `handle position uses its own track without moving gallery content`() {
        assertEquals(80, GalleryFastScrollLayoutPolicy.handleTop(0, 1_200, 400, 80, 920, 36))
        assertEquals(884, GalleryFastScrollLayoutPolicy.handleTop(800, 1_200, 400, 80, 920, 36))
    }

    @Test
    fun `drag progress reaches both ends of the independent track`() {
        assertEquals(0f, GalleryFastScrollLayoutPolicy.dragProgress(98f, 18f, 80, 920, 36))
        assertEquals(1f, GalleryFastScrollLayoutPolicy.dragProgress(902f, 18f, 80, 920, 36))
    }

    @Test
    fun `drag responds immediately beyond the track start`() {
        assertTrue(GalleryFastScrollLayoutPolicy.dragProgress(99f, 18f, 80, 920, 36) > 0f)
    }

    @Test
    fun `small drag movement produces a fractional row offset`() {
        assertEquals(
            GalleryFastScrollTarget(position = 0, positionOffsetFraction = 0.5f),
            GalleryFastScrollLayoutPolicy.target(itemCount = 5, progress = 0.125f),
        )
        assertEquals(
            GalleryFastScrollTarget(position = 4, positionOffsetFraction = 0f),
            GalleryFastScrollLayoutPolicy.target(itemCount = 5, progress = 1f),
        )
    }

    @Test
    fun `footer clears the selection bar only while it is shown`() {
        assertEquals(
            102,
            GalleryFastScrollLayoutPolicy.footerHeight(
                selectionBarVisible = true,
                bottomInset = 24,
                selectionClearance = 78,
                baseClearance = 12,
            ),
        )
        assertEquals(
            36,
            GalleryFastScrollLayoutPolicy.footerHeight(
                selectionBarVisible = false,
                bottomInset = 24,
                selectionClearance = 78,
                baseClearance = 12,
            ),
        )
    }
}
