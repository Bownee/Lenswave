package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryNavigationPolicyTest {
    private val collections = listOf(
        GalleryDestination.Tag(ProtonMediaTag.VIDEOS),
        GalleryDestination.AlbumPhotos(ProtonAlbumReference("album", "Album")),
        GalleryDestination.Trash,
    )

    @Test
    fun `the timeline is the Photos tab and everything else is the Library tab`() {
        assertEquals(GalleryTab.PHOTOS, GalleryNavigationPolicy.tab(GalleryDestination.Timeline))
        assertEquals(GalleryTab.LIBRARY, GalleryNavigationPolicy.tab(GalleryDestination.Library))
        collections.forEach { assertEquals(GalleryTab.LIBRARY, GalleryNavigationPolicy.tab(it)) }
    }

    @Test
    fun `collections return to the Library and tab roots have no parent`() {
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.parent(it)) }
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.Timeline))
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.Library))
    }

    @Test
    fun `cold start remembers the tab root instead of a deep collection`() {
        assertEquals(GalleryDestination.Timeline, GalleryNavigationPolicy.root(GalleryDestination.Timeline))
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.root(it)) }
    }

    @Test
    fun `losing the account keeps tab roots and returns collections to the Library`() {
        assertEquals(
            GalleryDestination.Timeline,
            GalleryNavigationPolicy.withoutAccount(GalleryDestination.Timeline),
        )
        assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.withoutAccount(GalleryDestination.Library))
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.withoutAccount(it)) }
    }
}
