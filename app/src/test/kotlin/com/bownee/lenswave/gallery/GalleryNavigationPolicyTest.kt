package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryNavigationPolicyTest {
    private val filter = GalleryDestination.Tag(ProtonMediaTag.VIDEOS)
    private val album = GalleryDestination.AlbumPhotos(ProtonAlbumReference("album", "Album"))
    private val subPages = listOf(album)

    @Test
    fun `the timeline and its filters are the Photos tab, albums the Albums tab`() {
        assertEquals(GalleryTab.PHOTOS, GalleryNavigationPolicy.tab(GalleryDestination.Timeline))
        assertEquals(GalleryTab.PHOTOS, GalleryNavigationPolicy.tab(filter))
        assertEquals(GalleryTab.ALBUMS, GalleryNavigationPolicy.tab(GalleryDestination.Library))
        subPages.forEach { assertEquals(GalleryTab.ALBUMS, GalleryNavigationPolicy.tab(it)) }
    }

    @Test
    fun `back returns filters to the timeline and sub-pages to the album list`() {
        assertEquals(GalleryDestination.Timeline, GalleryNavigationPolicy.parent(filter))
        subPages.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.parent(it)) }
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.Timeline))
        assertNull(GalleryNavigationPolicy.parent(GalleryDestination.Library))
    }

    @Test
    fun `only album sub-pages show a back button and only Photos pages show filters`() {
        subPages.forEach { assertTrue(GalleryNavigationPolicy.showsBack(it)) }
        assertFalse(GalleryNavigationPolicy.showsBack(filter))
        assertFalse(GalleryNavigationPolicy.showsBack(GalleryDestination.Timeline))
        assertFalse(GalleryNavigationPolicy.showsBack(GalleryDestination.Library))
        assertTrue(GalleryNavigationPolicy.showsFilters(GalleryDestination.Timeline))
        assertTrue(GalleryNavigationPolicy.showsFilters(filter))
        assertFalse(GalleryNavigationPolicy.showsFilters(GalleryDestination.Library))
        subPages.forEach { assertFalse(GalleryNavigationPolicy.showsFilters(it)) }
    }

    @Test
    fun `cold start remembers the tab root instead of a deep collection`() {
        assertEquals(GalleryDestination.Timeline, GalleryNavigationPolicy.root(GalleryDestination.Timeline))
        assertEquals(GalleryDestination.Timeline, GalleryNavigationPolicy.root(filter))
        subPages.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.root(it)) }
    }

    @Test
    fun `losing the account keeps tab roots and returns collections to their root`() {
        assertEquals(GalleryDestination.Timeline, GalleryNavigationPolicy.withoutAccount(GalleryDestination.Timeline))
        assertEquals(GalleryDestination.Timeline, GalleryNavigationPolicy.withoutAccount(filter))
        assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.withoutAccount(GalleryDestination.Library))
        subPages.forEach { assertEquals(GalleryDestination.Library, GalleryNavigationPolicy.withoutAccount(it)) }
    }
}
