package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDestinationTest {
    @Test
    fun `each destination owns its source space`() {
        val album = ProtonAlbum("album", "Album", 0, null, 0, 0, false, false)

        assertEquals(GallerySpace.COMBINED, GalleryDestination.Combined.space)
        assertEquals(GallerySpace.DEVICE, GalleryDestination.Device().space)
        assertEquals(GallerySpace.DEVICE, GalleryDestination.Trash(PhotoSource.DEVICE).space)
        assertEquals(GallerySpace.PROTON, GalleryDestination.ProtonTimeline.space)
        assertEquals(GallerySpace.PROTON, GalleryDestination.ProtonAlbums.space)
        assertEquals(GallerySpace.PROTON, GalleryDestination.ProtonAlbumPhotos(album.reference()).space)
        assertEquals(GallerySpace.PROTON, GalleryDestination.Trash(PhotoSource.PROTON).space)
    }

    @Test
    fun `each space opens its timeline by default`() {
        assertEquals(GalleryDestination.Combined, GalleryDestinations.defaultFor(GallerySpace.COMBINED))
        assertEquals(GalleryDestination.ProtonTimeline, GalleryDestinations.defaultFor(GallerySpace.PROTON))
        assertEquals(GalleryDestination.Device(), GalleryDestinations.defaultFor(GallerySpace.DEVICE))
    }
}
