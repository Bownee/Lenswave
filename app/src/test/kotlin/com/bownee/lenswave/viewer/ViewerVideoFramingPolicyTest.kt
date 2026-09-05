package com.bownee.lenswave.viewer

import com.bownee.lenswave.viewer.ViewerVideoFramingPolicy.Framing
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerVideoFramingPolicyTest {
    /** A phone's media box between the title and the action bar, as measured on a 1080x2340 screen. */
    private val phoneBox = 1080 to 1606

    @Test
    fun `a phone clip spans a phone-shaped box and loses a sliver top and bottom`() {
        val (boxWidth, boxHeight) = phoneBox

        assertEquals(Framing.FILL_WIDTH, ViewerVideoFramingPolicy.framing(1080, 1920, boxWidth, boxHeight))
        assertEquals(Framing.FILL_WIDTH, ViewerVideoFramingPolicy.framing(480, 864, boxWidth, boxHeight))
        assertEquals(0.164f, ViewerVideoFramingPolicy.croppedFraction(1080, 1920, boxWidth, boxHeight), 0.001f)
    }

    @Test
    fun `a landscape clip spans the width without any crop`() {
        val (boxWidth, boxHeight) = phoneBox

        assertEquals(Framing.FILL_WIDTH, ViewerVideoFramingPolicy.framing(1920, 1080, boxWidth, boxHeight))
        assertEquals(0f, ViewerVideoFramingPolicy.croppedFraction(1920, 1080, boxWidth, boxHeight), 0f)
    }

    @Test
    fun `a portrait clip in a landscape box is fitted rather than mostly cropped`() {
        assertEquals(Framing.FIT, ViewerVideoFramingPolicy.framing(1080, 1920, 2340, 1000))
        assertEquals(0.76f, ViewerVideoFramingPolicy.croppedFraction(1080, 1920, 2340, 1000), 0.001f)
    }

    @Test
    fun `the crop limit is the boundary`() {
        // A box exactly four fifths as tall as the spanned clip crops one fifth: still spanned.
        assertEquals(Framing.FILL_WIDTH, ViewerVideoFramingPolicy.framing(1000, 1000, 1000, 800))
        assertEquals(Framing.FIT, ViewerVideoFramingPolicy.framing(1000, 1000, 1000, 799))
    }

    @Test
    fun `a stand-in spanning the width is centred on the box like the clip will be`() {
        val (boxWidth, boxHeight) = phoneBox

        // The 1080x1920 clip needs no scaling and overhangs the 1606 box by 157 each side.
        assertEquals(
            ViewerVideoFramingPolicy.Placement(scale = 1f, offsetY = -157f),
            ViewerVideoFramingPolicy.fillWidthPlacement(1080, 1920, boxWidth, boxHeight),
        )
        // A 480x864 thumbnail scales up 2.25x to 1944 tall and overhangs by 169.
        assertEquals(
            ViewerVideoFramingPolicy.Placement(scale = 2.25f, offsetY = -169f),
            ViewerVideoFramingPolicy.fillWidthPlacement(480, 864, boxWidth, boxHeight),
        )
        // A landscape picture spans the width with room to spare below and above.
        assertEquals(
            ViewerVideoFramingPolicy.Placement(scale = 0.5625f, offsetY = 499.25f),
            ViewerVideoFramingPolicy.fillWidthPlacement(1920, 1080, boxWidth, boxHeight),
        )
    }

    @Test
    fun `a clip or box without a size is fitted`() {
        assertEquals(Framing.FIT, ViewerVideoFramingPolicy.framing(0, 0, 1080, 1606))
        assertEquals(Framing.FIT, ViewerVideoFramingPolicy.framing(1080, 1920, 0, 0))
    }
}
