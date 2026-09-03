package com.bownee.lenswave

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoViewerMediaLayoutPolicyTest {
    @Test
    fun `title section reduces media height without extending the page`() {
        assertEquals(920, PhotoViewerMediaLayoutPolicy.mediaHeight(1_000, 80))
    }

    @Test
    fun `missing title section leaves the full height for media`() {
        assertEquals(1_000, PhotoViewerMediaLayoutPolicy.mediaHeight(1_000, 0))
    }

    @Test
    fun `media stops above the actions with the requested gap`() {
        assertEquals(
            108,
            PhotoViewerMediaLayoutPolicy.bottomInset(
                viewportHeight = 1_000,
                actionsTop = 900,
                gap = 8,
            ),
        )
    }

    @Test
    fun `media inset waits for valid layout coordinates`() {
        assertEquals(0, PhotoViewerMediaLayoutPolicy.bottomInset(1_000, 0, 8))
        assertEquals(0, PhotoViewerMediaLayoutPolicy.bottomInset(0, 0, 8))
    }
}
