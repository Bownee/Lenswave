package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryNavigationPolicyTest {
    private val album = GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("album", "Album"))
    private val collections = listOf(
        GalleryDestination.Device(DeviceCollection.SCREENSHOTS),
        GalleryDestination.ProtonTag(ProtonMediaTag.VIDEOS),
        album,
        GalleryDestination.Trash(PhotoSource.PROTON),
        GalleryDestination.Trash(PhotoSource.DEVICE),
    )

    @Test
    fun `the Proton timeline is the Photos tab and everything else is the Library tab`() {
        assertEquals(GalleryTab.PHOTOS, GalleryNavigationPolicy.tab(GalleryDestination.ProtonTimeline))
        assertEquals(GalleryTab.LIBRARY, GalleryNavigationPolicy.tab(GalleryDestination.Library))
        collections.forEach { assertEquals(GalleryTab.LIBRARY, GalleryNavigationPolicy.tab(it)) }
    }

    @Test
    fun `collections return to the Library and tab roots have no parent`() {
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.parent(it)) }
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.ProtonTimeline))
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.Library))
    }

    @Test
    fun `cold start remembers the tab root instead of a deep collection`() {
        assertEquals(
            GalleryDestination.ProtonTimeline,
            GalleryNavigationPolicy.root(GalleryDestination.ProtonTimeline),
        )
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.root(it)) }
    }

    @Test
    fun `losing Proton keeps the timeline and returns Proton collections to the Library`() {
        assertEquals(
            GalleryDestination.ProtonTimeline,
            GalleryNavigationPolicy.withoutProton(GalleryDestination.ProtonTimeline),
        )
        assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.withoutProton(album))
        assertEquals(
            GalleryDestination.Library,
            GalleryNavigationPolicy.withoutProton(GalleryDestination.Trash(PhotoSource.PROTON)),
        )
        listOf(
            GalleryDestination.Device(DeviceCollection.DOWNLOADS),
            GalleryDestination.Trash(PhotoSource.DEVICE),
            GalleryDestination.Library,
        ).forEach {
            assertFalse(GalleryNavigationPolicy.requiresProton(it))
            assertEquals(it, GalleryNavigationPolicy.withoutProton(it))
        }
    }
}
