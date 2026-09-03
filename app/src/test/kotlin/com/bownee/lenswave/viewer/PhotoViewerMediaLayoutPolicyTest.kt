package com.bownee.lenswave.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoViewerMediaLayoutPolicyTest {
    @Test
    fun `media height follows the frame's own top`() {
        assertEquals(920, PhotoViewerMediaLayoutPolicy.mediaHeight(1_000, 80))
        assertEquals(1_000, PhotoViewerMediaLayoutPolicy.mediaHeight(1_000, 0))
    }

    @Test
    fun `media is inset equally by the taller overlay plus the gap`() {
        assertEquals(
            108,
            PhotoViewerMediaLayoutPolicy.verticalInset(
                viewportHeight = 1_000,
                titleBottom = 60,
                actionsTop = 900,
                gap = 8,
            ),
        )
        assertEquals(
            128,
            PhotoViewerMediaLayoutPolicy.verticalInset(
                viewportHeight = 1_000,
                titleBottom = 120,
                actionsTop = 900,
                gap = 8,
            ),
        )
    }

    @Test
    fun `inset never consumes more than half the viewport`() {
        assertEquals(500, PhotoViewerMediaLayoutPolicy.verticalInset(1_000, 900, 100, 8))
    }

    @Test
    fun `media inset waits for valid layout coordinates`() {
        assertEquals(0, PhotoViewerMediaLayoutPolicy.verticalInset(1_000, 0, 0, 8))
        assertEquals(0, PhotoViewerMediaLayoutPolicy.verticalInset(0, 0, 0, 8))
    }
}
