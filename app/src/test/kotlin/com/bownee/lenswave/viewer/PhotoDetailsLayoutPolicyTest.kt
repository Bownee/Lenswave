package com.bownee.lenswave.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDetailsLayoutPolicyTest {
    @Test
    fun `sheet attachment overlaps the fitted image by the requested amount`() {
        assertEquals(
            508,
            PhotoDetailsLayoutPolicy.attachmentOffset(
                mediaHeight = 2_000,
                fittedImageBottom = 1_500f,
                overlap = 8,
            ),
        )
    }

    @Test
    fun `fractional image edge rounds toward a tiny overlap instead of a gap`() {
        assertEquals(
            508,
            PhotoDetailsLayoutPolicy.attachmentOffset(
                mediaHeight = 2_000,
                fittedImageBottom = 1_500.4f,
                overlap = 8,
            ),
        )
    }

    @Test
    fun `initial scroll keeps the attached boundary at the intended height`() {
        assertEquals(
            592,
            PhotoDetailsLayoutPolicy.initialOffset(
                mediaHeight = 2_000,
                fittedImageBottom = 1_500f,
                overlap = 8,
                fallbackOffset = 1_100,
                maximumOffset = 1_500,
            ),
        )
    }

    @Test
    fun `fallback is used until image dimensions are available`() {
        assertEquals(
            1_100,
            PhotoDetailsLayoutPolicy.initialOffset(
                mediaHeight = 2_000,
                fittedImageBottom = null,
                overlap = 8,
                fallbackOffset = 1_100,
                maximumOffset = 1_500,
            ),
        )
    }

    @Test
    fun `offset stays within scrollable content`() {
        assertEquals(
            0,
            PhotoDetailsLayoutPolicy.initialOffset(
                mediaHeight = 2_000,
                fittedImageBottom = 1_000f,
                overlap = 8,
                fallbackOffset = 0,
                maximumOffset = 300,
            ),
        )
    }

    @Test
    fun `maximum scroll stops at translated sheet bottom`() {
        assertEquals(
            800,
            PhotoDetailsLayoutPolicy.maximumOffset(
                surfaceHeight = 3_300,
                viewportHeight = 2_000,
                attachmentOffset = 500,
            ),
        )
    }

    @Test
    fun `an open sheet waits for the surface to be measured`() {
        assertTrue(PhotoDetailsLayoutPolicy.awaitsLayout(shown = true, surfaceHeight = 0, viewportHeight = 2_000))
        assertTrue(PhotoDetailsLayoutPolicy.awaitsLayout(shown = true, surfaceHeight = 3_300, viewportHeight = 0))
        assertFalse(PhotoDetailsLayoutPolicy.awaitsLayout(shown = true, surfaceHeight = 3_300, viewportHeight = 2_000))
    }

    @Test
    fun `a closed sheet never waits for layout`() {
        assertFalse(PhotoDetailsLayoutPolicy.awaitsLayout(shown = false, surfaceHeight = 0, viewportHeight = 0))
    }
}
