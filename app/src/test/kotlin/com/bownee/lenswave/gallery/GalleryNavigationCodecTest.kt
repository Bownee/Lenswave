package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryNavigationCodecTest {
    @Test
    fun `all gallery screens survive a persistence round trip`() {
        val destinations = listOf(
            GalleryDestination.Combined,
            GalleryDestination.Device(DeviceCollection.SCREENSHOTS),
            GalleryDestination.ProtonTimeline,
            GalleryDestination.ProtonTag(ProtonMediaTag.FAVORITES),
            GalleryDestination.ProtonAlbums,
            GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("album-id", "Favorites")),
            GalleryDestination.Trash(PhotoSource.DEVICE),
            GalleryDestination.Trash(PhotoSource.PROTON),
        )

        destinations.forEach { destination ->
            val state = GalleryNavigationState(destination, DeviceCollection.SCREENSHOTS)

            assertEquals(state, GalleryNavigationCodec.decode(GalleryNavigationCodec.encode(state)))
        }
    }

    @Test
    fun `missing or unknown destination is not treated as a previous screen`() {
        assertNull(GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = null)))
        assertNull(GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "unknown")))
    }

    @Test
    fun `incomplete nested screens fall back to a usable parent screen`() {
        assertEquals(
            GalleryNavigationState(GalleryDestination.ProtonAlbums),
            GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "proton-album")),
        )
        assertEquals(
            GalleryNavigationState(
                GalleryDestination.Device(DeviceCollection.DOWNLOADS),
                DeviceCollection.DOWNLOADS,
            ),
            GalleryNavigationCodec.decode(
                StoredGalleryNavigation(
                    destination = "trash",
                    deviceCollection = DeviceCollection.DOWNLOADS.name,
                ),
            ),
        )
    }
}
