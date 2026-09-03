package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryStickyDateControllerTest {
    private val labels = mapOf(0 to "Mon, 1 Jan 2024", 1 to "Mon, 1 Jan 2024", 2 to "Sun, 31 Dec 2023")

    @Test
    fun `no label while a list header view is at the top`() {
        assertNull(GalleryStickyDateController.labelFor(0, headerViewsCount = 1, dateLabelForPosition = labels::get))
    }

    @Test
    fun `list positions are translated into adapter positions`() {
        assertEquals(
            "Sun, 31 Dec 2023",
            GalleryStickyDateController.labelFor(3, headerViewsCount = 1, dateLabelForPosition = labels::get),
        )
    }

    @Test
    fun `without header views the list position is the adapter position`() {
        assertEquals(
            "Mon, 1 Jan 2024",
            GalleryStickyDateController.labelFor(1, headerViewsCount = 0, dateLabelForPosition = labels::get),
        )
    }

    @Test
    fun `positions without a date carry no label`() {
        assertNull(GalleryStickyDateController.labelFor(9, headerViewsCount = 1, dateLabelForPosition = labels::get))
    }

    @Test
    fun `the first row below the pinned header drives the label`() {
        // Children at list positions 4, 5, 6 with bottoms 80, 200, 320; the header ends at 120.
        assertEquals(5, GalleryStickyDateController.firstRowBelow(4, listOf(80, 200, 320), headerBottom = 120))
        assertEquals(4, GalleryStickyDateController.firstRowBelow(4, listOf(121, 200), headerBottom = 120))
        assertNull(GalleryStickyDateController.firstRowBelow(4, listOf(80, 100), headerBottom = 120))
        assertNull(GalleryStickyDateController.firstRowBelow(0, emptyList(), headerBottom = 120))
    }
}
