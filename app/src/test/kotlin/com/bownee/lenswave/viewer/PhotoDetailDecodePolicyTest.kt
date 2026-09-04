package com.bownee.lenswave.viewer

import com.bownee.lenswave.viewer.PhotoDetailDecodePolicy.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoDetailDecodePolicyTest {
    @Test
    fun `no detail at fit scale when the base already matches the display`() {
        // 4000 x 3000 on a 1080 px wide screen, base sample 2: 0.27 * 2 = 0.54 image px per screen px.
        assertNull(PhotoDetailDecodePolicy.sampleSize(scale = 0.27f, baseSampleSize = 2))
        // 8000 x 6000 with base sample 4 at the same fit.
        assertNull(PhotoDetailDecodePolicy.sampleSize(scale = 0.135f, baseSampleSize = 4))
        assertNull(
            PhotoDetailDecodePolicy.plan(
                scale = 0.135f,
                baseSampleSize = 4,
                visible = Region(0, 0, 8_000, 6_000),
                imageWidth = 8_000,
                imageHeight = 6_000,
            ),
        )
    }

    @Test
    fun `a base decoded at full resolution never needs detail`() {
        assertNull(PhotoDetailDecodePolicy.sampleSize(scale = 3f, baseSampleSize = 1))
    }

    @Test
    fun `two times zoom decodes at the largest sample that still matches the display`() {
        // Base sample 2 at 0.54: only the full-resolution tile is sharper than the display.
        assertEquals(1, PhotoDetailDecodePolicy.sampleSize(scale = 0.54f, baseSampleSize = 2))
        // Base sample 4 at 0.27: sample 2 gives 0.54 image px per screen px, still at least one.
        assertEquals(2, PhotoDetailDecodePolicy.sampleSize(scale = 0.27f, baseSampleSize = 4))
        // Sample 2 would be as blurry as the display; sample 1 is needed.
        assertEquals(1, PhotoDetailDecodePolicy.sampleSize(scale = 0.6f, baseSampleSize = 4))
    }

    @Test
    fun `the region keeps a quarter margin and is clamped to the image`() {
        val plan =
            PhotoDetailDecodePolicy.plan(
                scale = 0.54f,
                baseSampleSize = 2,
                visible = Region(100, 0, 1_100, 800),
                imageWidth = 4_000,
                imageHeight = 3_000,
            )
        assertNotNull(plan)
        assertEquals(1, plan!!.sampleSize)
        // Left margin of 250 is clamped at the image edge; the others extend fully.
        assertEquals(Region(0, 0, 1_350, 1_000), plan.region)
    }

    @Test
    fun `a viewport outside the image yields nothing`() {
        assertNull(
            PhotoDetailDecodePolicy.plan(
                scale = 0.54f,
                baseSampleSize = 2,
                visible = Region(5_000, 0, 6_000, 800),
                imageWidth = 4_000,
                imageHeight = 3_000,
            ),
        )
    }

    @Test
    fun `the margin is dropped before the tile exceeds the pixel budget`() {
        // 2000 x 1600 at sample 1 with margin would be 3000 x 2400 = 7.2 M pixels.
        val plan =
            PhotoDetailDecodePolicy.plan(
                scale = 0.54f,
                baseSampleSize = 2,
                visible = Region(1_000, 1_000, 3_000, 2_600),
                imageWidth = 6_000,
                imageHeight = 5_000,
            )
        assertEquals(PhotoDetailDecodePolicy.Plan(1, Region(1_000, 1_000, 3_000, 2_600)), plan)
    }

    @Test
    fun `the sample rises when even the bare viewport exceeds the pixel budget`() {
        // 4000 x 6000 visible: 24 M pixels at sample 2 is 6 M, at sample 4 it is 1.5 M.
        val plan =
            PhotoDetailDecodePolicy.plan(
                scale = 0.27f,
                baseSampleSize = 8,
                visible = Region(0, 0, 4_000, 6_000),
                imageWidth = 12_000,
                imageHeight = 8_000,
            )
        assertEquals(PhotoDetailDecodePolicy.Plan(4, Region(0, 0, 4_000, 6_000)), plan)
    }

    @Test
    fun `no detail when only the base sample fits the budget`() {
        // 6000 x 6000 visible: 9 M at sample 2; sample 4 equals the base, so nothing sharper fits.
        assertNull(
            PhotoDetailDecodePolicy.plan(
                scale = 0.3f,
                baseSampleSize = 4,
                visible = Region(0, 0, 6_000, 6_000),
                imageWidth = 8_000,
                imageHeight = 8_000,
            ),
        )
    }
}
