package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryNavigationPolicyTest {
    private val album = GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("album", "Album"))
    private val timelines = listOf(
        GalleryDestination.Combined,
        GalleryDestination.ProtonTimeline,
        GalleryDestination.Device(),
    )
    private val collections = listOf(
        GalleryDestination.Device(DeviceCollection.SCREENSHOTS),
        GalleryDestination.ProtonTag(ProtonMediaTag.VIDEOS),
        album,
        GalleryDestination.Trash(PhotoSource.PROTON),
        GalleryDestination.Trash(PhotoSource.DEVICE),
    )

    @Test
    fun `timelines live in the Photos tab and everything else in the Library tab`() {
        timelines.forEach { assertEquals(GalleryTab.PHOTOS, GalleryNavigationPolicy.tab(it)) }
        assertEquals(GalleryTab.LIBRARY, GalleryNavigationPolicy.tab(GalleryDestination.Library))
        collections.forEach { assertEquals(GalleryTab.LIBRARY, GalleryNavigationPolicy.tab(it)) }
    }

    @Test
    fun `each source maps to exactly one timeline`() {
        GallerySource.entries.forEach { source ->
            val timeline = GalleryNavigationPolicy.timeline(source)
            assertEquals(source, GalleryNavigationPolicy.selectedSource(timeline))
            assertEquals(timeline, GalleryNavigationPolicy.withSource(GalleryDestination.Library, source))
        }
    }

    @Test
    fun `timelines offer every source and trash offers its two stores`() {
        timelines.forEach {
            assertEquals(GallerySource.entries, GalleryNavigationPolicy.sources(it, supportsDeviceTrash = false))
        }
        val trash = GalleryDestination.Trash(PhotoSource.PROTON)
        assertEquals(
            listOf(GallerySource.PROTON, GallerySource.DEVICE),
            GalleryNavigationPolicy.sources(trash, supportsDeviceTrash = true),
        )
        assertEquals(listOf(GallerySource.PROTON), GalleryNavigationPolicy.sources(trash, supportsDeviceTrash = false))
        assertTrue(GalleryNavigationPolicy.sources(GalleryDestination.Library, supportsDeviceTrash = true).isEmpty())
        assertTrue(GalleryNavigationPolicy.sources(album, supportsDeviceTrash = true).isEmpty())
    }

    @Test
    fun `switching source inside trash stays in trash`() {
        val trash = GalleryDestination.Trash(PhotoSource.PROTON)

        assertEquals(
            GalleryDestination.Trash(PhotoSource.DEVICE),
            GalleryNavigationPolicy.withSource(trash, GallerySource.DEVICE),
        )
        assertEquals(trash, GalleryNavigationPolicy.withSource(trash, GallerySource.PROTON))
        assertEquals(GallerySource.PROTON, GalleryNavigationPolicy.selectedSource(trash))
    }

    @Test
    fun `collections return to the Library and tab roots have no parent`() {
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.parent(it)) }
        timelines.forEach { assertNull(GalleryNavigationPolicy.parent(it)) }
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.Library))
    }

    @Test
    fun `cold start remembers the tab root instead of a deep collection`() {
        timelines.forEach { assertEquals(it, GalleryNavigationPolicy.root(it)) }
        collections.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.root(it)) }
    }

    @Test
    fun `losing Proton falls back to the device timeline or the Library`() {
        assertEquals(GalleryDestination.Device(), GalleryNavigationPolicy.withoutProton(GalleryDestination.Combined))
        assertEquals(
            GalleryDestination.Device(),
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
