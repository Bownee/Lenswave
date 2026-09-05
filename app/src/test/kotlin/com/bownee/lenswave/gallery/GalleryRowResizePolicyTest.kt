package com.bownee.lenswave.gallery

import com.bownee.lenswave.gallery.GalleryRowResizePolicy.Margins
import com.bownee.lenswave.gallery.GalleryRowResizePolicy.Resize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRowResizePolicyTest {
    @Test
    fun aRowGrowsOrShrinksByTheDifferenceAndNeverBoth() {
        assertEquals(
            Resize(removeCount = 0, addCount = 3),
            GalleryRowResizePolicy.resize(currentCells = 3, columns = 6),
        )
        assertEquals(
            Resize(removeCount = 2, addCount = 0),
            GalleryRowResizePolicy.resize(currentCells = 6, columns = 4),
        )
        assertEquals(
            Resize(removeCount = 0, addCount = 0),
            GalleryRowResizePolicy.resize(currentCells = 4, columns = 4),
        )
        assertEquals(
            "an empty row is filled",
            Resize(0, 5),
            GalleryRowResizePolicy.resize(currentCells = 0, columns = 5),
        )
    }

    @Test
    fun onlyAnUnchangedSpanIsANoOp() {
        assertTrue(GalleryRowResizePolicy.resize(3, 3).isNoOp)
        assertFalse(GalleryRowResizePolicy.resize(3, 4).isNoOp)
        assertFalse(GalleryRowResizePolicy.resize(4, 3).isNoOp)
    }

    @Test
    fun theGapIsSplitBetweenNeighboursAndAbsentAtTheRowEdges() {
        val gap = 3
        assertEquals(
            Margins(start = 0, end = 2),
            GalleryRowResizePolicy.cellMargins(column = 0, columns = 4, gap = gap),
        )
        assertEquals(
            Margins(start = 1, end = 2),
            GalleryRowResizePolicy.cellMargins(column = 1, columns = 4, gap = gap),
        )
        assertEquals(
            Margins(start = 1, end = 2),
            GalleryRowResizePolicy.cellMargins(column = 2, columns = 4, gap = gap),
        )
        assertEquals(
            Margins(start = 1, end = 0),
            GalleryRowResizePolicy.cellMargins(column = 3, columns = 4, gap = gap),
        )
    }

    @Test
    fun neighboursShareExactlyOneGapWhateverTheParity() {
        for (gap in 1..5) {
            val left = GalleryRowResizePolicy.cellMargins(column = 1, columns = 3, gap = gap)
            val right = GalleryRowResizePolicy.cellMargins(column = 2, columns = 3, gap = gap)
            assertEquals("gap $gap", gap, left.end + right.start)
        }
    }

    @Test
    fun aSingleCellRowHasNoMargins() {
        assertEquals(Margins(0, 0), GalleryRowResizePolicy.cellMargins(column = 0, columns = 1, gap = 4))
    }
}
