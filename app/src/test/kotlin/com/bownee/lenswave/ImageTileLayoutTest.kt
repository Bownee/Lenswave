package com.bownee.lenswave

import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTileLayoutTest {
    @Test
    fun rotatedExifTileIsPlacedInFullResolutionOutput() {
        val placement = ImageTileLayout.place(100, 50, 80, 60, 400, 300, 6, 1)
        assertEquals(220, placement.left)
        assertEquals(190, placement.top)
        assertEquals(80, placement.width)
        assertEquals(60, placement.height)
    }

    @Test
    fun everyExifOrientationKeepsTilesInsideOutputBounds() {
        for (orientation in 1..8) {
            for (rotation in 0..3) {
                val output = ImageTileLayout.outputSize(403, 307, orientation, rotation)
                val placement = ImageTileLayout.place(300, 200, 103, 107, 403, 307, orientation, rotation)
                assertTrue(placement.left >= 0)
                assertTrue(placement.top >= 0)
                assertTrue(placement.left + placement.width <= output.width)
                assertTrue(placement.top + placement.height <= output.height)
            }
        }
    }

    @Test
    fun outputDimensionsFollowExifAndEditorRotation() {
        val exifRotated = ImageTileLayout.outputSize(4000, 3000, 6, 0)
        val rotatedAgain = ImageTileLayout.outputSize(4000, 3000, 6, 1)
        assertEquals(3000, exifRotated.width)
        assertEquals(4000, exifRotated.height)
        assertEquals(4000, rotatedAgain.width)
        assertEquals(3000, rotatedAgain.height)
    }

    @Test
    fun transformedTilesCoverEveryOutputPixelExactlyOnce() {
        val imageWidth = 17
        val imageHeight = 13
        val tileEdge = 5
        for (orientation in 1..8) {
            for (rotation in 0..3) {
                val output = ImageTileLayout.outputSize(imageWidth, imageHeight, orientation, rotation)
                val coverage = Array(output.height) { IntArray(output.width) }
                for (top in 0 until imageHeight step tileEdge) {
                    val tileHeight = min(tileEdge, imageHeight - top)
                    for (left in 0 until imageWidth step tileEdge) {
                        val tileWidth = min(tileEdge, imageWidth - left)
                        val placement = ImageTileLayout.place(
                            left,
                            top,
                            tileWidth,
                            tileHeight,
                            imageWidth,
                            imageHeight,
                            orientation,
                            rotation,
                        )
                        for (y in placement.top until placement.top + placement.height) {
                            for (x in placement.left until placement.left + placement.width) coverage[y][x]++
                        }
                    }
                }
                coverage.forEach { row -> row.forEach { assertEquals(1, it) } }
            }
        }
    }
}
