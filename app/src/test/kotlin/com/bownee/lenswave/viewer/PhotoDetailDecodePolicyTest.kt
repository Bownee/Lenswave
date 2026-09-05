package com.bownee.lenswave.viewer

import com.bownee.lenswave.viewer.PhotoDetailDecodePolicy.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDetailDecodePolicyTest {
    /** The fixed budget the policy used before it was sized from the viewport. */
    private val legacyBudget = 4_000_000L

    /** A 1080 x 2400 portrait phone screen showing a picture that may carry transparency. */
    private val phoneBudget = PhotoDetailDecodePolicy.budget(1_080, 2_400, opaque = false)

    @Test
    fun `the budget is twice the view's pixels with a floor for small views`() {
        assertEquals(2L * 1_080 * 2_400, phoneBudget)
        val floorPixels = PhotoDetailDecodePolicy.MIN_DETAIL_BYTES / 4
        assertEquals(1_000_000L, floorPixels)
        assertEquals(floorPixels, PhotoDetailDecodePolicy.budget(200, 300, opaque = false))
        assertEquals(floorPixels, PhotoDetailDecodePolicy.budget(0, 0, opaque = false))
    }

    @Test
    fun `an opaque photo's 16-bit tile carries twice the pixels for the same memory`() {
        assertEquals(2 * phoneBudget, PhotoDetailDecodePolicy.budget(1_080, 2_400, opaque = true))
        assertEquals(
            PhotoDetailDecodePolicy.MIN_DETAIL_BYTES / 2,
            PhotoDetailDecodePolicy.budget(200, 300, opaque = true),
        )
        assertEquals(2, PhotoDetailDecodePolicy.bytesPerPixel(opaque = true))
        assertEquals(4, PhotoDetailDecodePolicy.bytesPerPixel(opaque = false))
    }

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
                budgetPixels = legacyBudget,
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
                budgetPixels = legacyBudget,
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
                budgetPixels = legacyBudget,
            ),
        )
        assertNull(
            PhotoDetailDecodePolicy.plan(
                scale = 0.54f,
                baseSampleSize = 2,
                visible = Region(0, 0, 1_000, 800),
                imageWidth = 0,
                imageHeight = 3_000,
                budgetPixels = legacyBudget,
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
                budgetPixels = legacyBudget,
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
                budgetPixels = legacyBudget,
            )
        assertEquals(PhotoDetailDecodePolicy.Plan(4, Region(0, 0, 4_000, 6_000)), plan)
    }

    @Test
    fun `the region shrinks towards the centre when only the base sample would fit`() {
        // 6000 x 6000 visible: 9 M at sample 2; sample 4 equals the base, so the tile shrinks instead.
        val visible = Region(0, 0, 6_000, 6_000)
        val plan =
            PhotoDetailDecodePolicy.plan(
                scale = 0.3f,
                baseSampleSize = 4,
                visible = visible,
                imageWidth = 8_000,
                imageHeight = 8_000,
                budgetPixels = legacyBudget,
            )
        assertNotNull(plan)
        assertEquals(2, plan!!.sampleSize)
        assertCentredWithin(plan.region, visible)
        assertTrue(decodedPixels(plan.region, plan.sampleSize) <= legacyBudget)
    }

    @Test
    fun `a 48 megapixel photo at twice its fit zoom still gets a tile`() {
        // 8000 x 6000 fits a 1080 px wide screen at 0.135; the base is sample 4 (3 M pixels).
        // At 2x the viewport is 4000 x 6000 image pixels: 6 M at sample 2 and over any budget.
        val visible = Region(2_000, 0, 6_000, 6_000)
        val plan =
            PhotoDetailDecodePolicy.plan(
                scale = 0.27f,
                baseSampleSize = 4,
                visible = visible,
                imageWidth = 8_000,
                imageHeight = 6_000,
                budgetPixels = phoneBudget,
            )
        assertNotNull(plan)
        assertEquals(2, plan!!.sampleSize)
        assertCentredWithin(plan.region, visible)
        assertTrue(decodedPixels(plan.region, plan.sampleSize) <= phoneBudget)
        // Most of the viewport is still covered: the shrink is a trim, not a stamp.
        assertTrue(plan.region.width >= visible.width * 9 / 10)
        assertTrue(plan.region.height >= visible.height * 9 / 10)
    }

    @Test
    fun `the sample rises before the region shrinks while it still beats the base`() {
        // 4000 x 3000 at 2x on the phone: the 2000 x 3000 viewport is 6 M at sample 1, over the
        // 5.2 M budget, but at sample 2 (still twice as sharp as the base) it fits whole.
        val plan =
            PhotoDetailDecodePolicy.plan(
                scale = 0.54f,
                baseSampleSize = 4,
                visible = Region(1_000, 0, 3_000, 3_000),
                imageWidth = 4_000,
                imageHeight = 3_000,
                budgetPixels = phoneBudget,
            )
        assertEquals(PhotoDetailDecodePolicy.Plan(2, Region(1_000, 0, 3_000, 3_000)), plan)
    }

    @Test
    fun `an empty budget yields nothing`() {
        assertNull(
            PhotoDetailDecodePolicy.plan(
                scale = 0.54f,
                baseSampleSize = 2,
                visible = Region(0, 0, 1_000, 800),
                imageWidth = 4_000,
                imageHeight = 3_000,
                budgetPixels = 0L,
            ),
        )
    }

    private fun assertCentredWithin(
        region: Region,
        viewport: Region,
    ) {
        assertTrue(region.left >= viewport.left && region.right <= viewport.right)
        assertTrue(region.top >= viewport.top && region.bottom <= viewport.bottom)
        val centreX = (viewport.left + viewport.right) / 2
        val centreY = (viewport.top + viewport.bottom) / 2
        assertTrue(region.left <= centreX && centreX <= region.right)
        assertTrue(region.top <= centreY && centreY <= region.bottom)
        assertTrue(region.left - viewport.left <= viewport.right - region.right + 1)
        assertTrue(region.top - viewport.top <= viewport.bottom - region.bottom + 1)
    }

    private fun decodedPixels(
        region: Region,
        sample: Int,
    ): Long = (region.width.toLong() / sample) * (region.height.toLong() / sample)
}
