package com.bownee.lenswave

import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExportBudgetTest {
    @Test
    fun ordinaryPhotoKeepsOriginalDimensions() {
        assertEquals(0, ImageProcessor.exportLongEdge(4000, 3000, 512L * 1024L * 1024L))
    }

    @Test
    fun hugePhotoIsCappedBeforeBitmapAllocation() {
        val longEdge = ImageProcessor.exportLongEdge(12000, 9000, 512L * 1024L * 1024L)
        assertTrue(longEdge in 1 until 12000)
        val scale = longEdge / 12000.0
        assertTrue(ceil(12000 * scale) * ceil(9000 * scale) <= 16_100_000.0)
    }

    @Test
    fun lowHeapUsesSmallerBound() {
        val longEdge = ImageProcessor.exportLongEdge(6000, 4000, 64L * 1024L * 1024L)
        assertTrue(longEdge in 1 until 6000)
    }
}
