package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryScrollPositionStoreTest {
    @Test
    fun destinationsKeepIndependentScrollPositions() {
        val store = GalleryScrollPositionStore()
        val timeline = GalleryDestination.ProtonTimeline
        val albums = GalleryDestination.Library
        val trash = GalleryDestination.Trash(PhotoSource.PROTON)

        store.save(timeline, GalleryScrollPosition(42, -17))
        store.save(albums, GalleryScrollPosition(8, -3))
        store.save(trash, GalleryScrollPosition(19, 4))

        assertEquals(GalleryScrollPosition(42, -17), store.positionFor(timeline))
        assertEquals(GalleryScrollPosition(8, -3), store.positionFor(albums))
        assertEquals(GalleryScrollPosition(19, 4), store.positionFor(trash))
        assertNull(store.positionFor(GalleryDestination.Device(DeviceCollection.CAMERA)))
    }

    @Test
    fun individualAlbumsKeepIndependentScrollPositions() {
        val store = GalleryScrollPositionStore()
        val first = GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("first", "First"))
        val second = GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("second", "Second"))

        store.save(first, GalleryScrollPosition(12, -9))
        store.save(second, GalleryScrollPosition(27, -2))

        assertEquals(GalleryScrollPosition(12, -9), store.positionFor(first))
        assertEquals(GalleryScrollPosition(27, -2), store.positionFor(second))
    }
}
