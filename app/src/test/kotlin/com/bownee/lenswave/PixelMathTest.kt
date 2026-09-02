package com.bownee.lenswave

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelMathTest {
    @Test
    fun neutralAdjustmentsLeavePixelUnchanged() {
        val original = 0xff4a789c.toInt()
        val adjusted = PixelMath.adjustPixel(original, 0.5f, 0.5f, PhotoAdjustments.NEUTRAL)
        assertEquals(original, adjusted)
    }

    @Test
    fun brightnessIncreasesLuminance() {
        val original = 0xff303030.toInt()
        val brighter = PhotoAdjustments.NEUTRAL.withValue(PhotoAdjustments.BRIGHTNESS, 0.2f)
        val adjusted = PixelMath.adjustPixel(original, 0.5f, 0.5f, brighter)
        assertTrue((adjusted and 0xff) > (original and 0xff))
    }

    @Test
    fun vignetteDarkensCornersButNotCenter() {
        val original = 0xffb0b0b0.toInt()
        val vignette = PhotoAdjustments.NEUTRAL.withValue(PhotoAdjustments.VIGNETTE, 1f)
        val center = PixelMath.adjustPixel(original, 0.5f, 0.5f, vignette)
        val corner = PixelMath.adjustPixel(original, 0f, 0f, vignette)
        assertEquals(original, center)
        assertTrue((corner and 0xff) < (center and 0xff))
    }

    @Test
    fun everyAdjustmentExtremeIsBoundedAndPreservesAlpha() {
        val original = 0x7f4a789c
        for (adjustment in PhotoAdjustments.BRIGHTNESS..PhotoAdjustments.VIGNETTE) {
            val minimum = if (adjustment == PhotoAdjustments.VIGNETTE) 0f else -1f
            for (value in floatArrayOf(minimum, 1f)) {
                val state = PhotoAdjustments.NEUTRAL.withValue(adjustment, value)
                val result = PixelMath.adjustPixel(original, 0f, 0f, state)
                assertEquals(0x7f, result ushr 24 and 0xff)
                assertTrue(result ushr 16 and 0xff in 0..255)
                assertTrue(result ushr 8 and 0xff in 0..255)
                assertTrue(result and 0xff in 0..255)
            }
        }
    }

    @Test
    fun gpuShaderIsGeneratedFromTheSharedAdjustmentSpec() {
        assertTrue(PhotoAdjustmentSpec.FRAGMENT_SHADER.contains("u_brightness"))
        assertTrue(PhotoAdjustmentSpec.FRAGMENT_SHADER.contains("u_vignette"))
        assertTrue(
            PhotoAdjustmentSpec.FRAGMENT_SHADER.contains(
                String.format(Locale.ROOT, "%f", PhotoAdjustmentSpec.LUMA_RED),
            ),
        )
        assertTrue(
            PhotoAdjustmentSpec.FRAGMENT_SHADER.contains(
                String.format(Locale.ROOT, "%f", PhotoAdjustmentSpec.VIGNETTE_STRENGTH),
            ),
        )
    }
}
