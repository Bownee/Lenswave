package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFastScrollThumbnailPolicyTest {
    @Test
    fun `fast scrolling uses only memory cached thumbnails`() {
        assertFalse(GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling = true))
        assertTrue(GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling = false))
    }

    @Test
    fun `ending fast scroll rebinds the final visible rows`() {
        assertTrue(
            GalleryFastScrollThumbnailPolicy.shouldRebind(
                wasFastScrolling = true,
                isFastScrolling = false,
            ),
        )
        assertFalse(
            GalleryFastScrollThumbnailPolicy.shouldRebind(
                wasFastScrolling = false,
                isFastScrolling = true,
            ),
        )
    }
}
