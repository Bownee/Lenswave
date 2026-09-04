package com.bownee.lenswave.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoBaseDecodePolicyTest {
    @Test
    fun `the budget follows the view with a floor`() {
        // 1080 x 2400 at 1.3 is about 3.4 M pixels, well under the old fixed 4 M.
        assertEquals((1.3 * 1_080 * 2_400).toLong(), PhotoBaseDecodePolicy.budget(1_080, 2_400, 1_080, 2_400))
        assertEquals(PhotoBaseDecodePolicy.MIN_BASE_PIXELS, PhotoBaseDecodePolicy.budget(400, 300, 1_080, 2_400))
    }

    @Test
    fun `an unmeasured view falls back to the display`() {
        assertEquals(
            PhotoBaseDecodePolicy.budget(1_080, 2_400, 1_080, 2_400),
            PhotoBaseDecodePolicy.budget(0, 0, 1_080, 2_400),
        )
        assertEquals(PhotoBaseDecodePolicy.MIN_BASE_PIXELS, PhotoBaseDecodePolicy.budget(0, 0, 0, 0))
    }

    @Test
    fun `the sample halves the picture until it fits the budget`() {
        val budget = PhotoBaseDecodePolicy.budget(1_080, 2_400, 1_080, 2_400)
        assertEquals(1, PhotoBaseDecodePolicy.sampleSize(1_920, 1_080, budget))
        // 12 M pixels: sample 2 gives 3 M, which fits.
        assertEquals(2, PhotoBaseDecodePolicy.sampleSize(4_000, 3_000, budget))
        // 48 M pixels: sample 2 is 12 M, sample 4 is 3 M.
        assertEquals(4, PhotoBaseDecodePolicy.sampleSize(8_000, 6_000, budget))
        assertEquals(1, PhotoBaseDecodePolicy.sampleSize(0, 0, budget))
    }

    @Test
    fun `opaque containers can use the half-size pixel format`() {
        assertTrue(PhotoBaseDecodePolicy.isOpaque("image/jpeg"))
        assertTrue(PhotoBaseDecodePolicy.isOpaque("image/heic"))
        assertTrue(PhotoBaseDecodePolicy.isOpaque("image/HEIF"))
        assertFalse(PhotoBaseDecodePolicy.isOpaque("image/png"))
        assertFalse(PhotoBaseDecodePolicy.isOpaque("image/webp"))
        assertFalse(PhotoBaseDecodePolicy.isOpaque("image/avif"))
        assertFalse(PhotoBaseDecodePolicy.isOpaque(null))
    }
}
