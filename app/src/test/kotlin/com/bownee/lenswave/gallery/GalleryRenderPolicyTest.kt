package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRenderPolicyTest {
    private val photo = GalleryAsset(stableId = "a", capturedAtEpochMillis = 1, nodeUid = "a", hasThumbnail = true)

    @Test
    fun `photo pages compare by reference only`() {
        val page = GalleryContent.Photos(listOf(photo))

        assertFalse(GalleryRenderPolicy.contentChanged(page, page))
        assertTrue(GalleryRenderPolicy.contentChanged(page, GalleryContent.Photos(listOf(photo))))
        assertTrue(GalleryRenderPolicy.contentChanged(null, page))
    }

    @Test
    fun `library pages compare structurally`() {
        val section = LibrarySection(key = "albums", title = "", items = emptyList())

        assertFalse(
            GalleryRenderPolicy.contentChanged(
                GalleryContent.Library(listOf(section)),
                GalleryContent.Library(listOf(section)),
            ),
        )
        assertTrue(
            GalleryRenderPolicy.contentChanged(
                GalleryContent.Library(emptyList()),
                GalleryContent.Library(listOf(section)),
            ),
        )
        assertTrue(
            GalleryRenderPolicy.contentChanged(GalleryContent.Photos(emptyList()), GalleryContent.Library(emptyList())),
        )
        assertTrue(
            GalleryRenderPolicy.contentChanged(GalleryContent.Library(emptyList()), GalleryContent.Photos(emptyList())),
        )
    }
}
