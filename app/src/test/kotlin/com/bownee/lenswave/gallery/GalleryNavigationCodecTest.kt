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
            GalleryDestination.ProtonTimeline,
            GalleryDestination.Library,
            GalleryDestination.Device(DeviceCollection.SCREENSHOTS),
            GalleryDestination.ProtonTag(ProtonMediaTag.FAVORITES),
            GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("album-id", "Favorites")),
            GalleryDestination.Trash(PhotoSource.DEVICE),
            GalleryDestination.Trash(PhotoSource.PROTON),
        )

        destinations.forEach { destination ->
            assertEquals(destination, GalleryNavigationCodec.decode(GalleryNavigationCodec.encode(destination)))
        }
    }

    @Test
    fun `missing or unknown destination is not treated as a previous screen`() {
        assertNull(GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = null)))
        assertNull(GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "unknown")))
    }

    @Test
    fun `incomplete collections fall back to the Library`() {
        listOf("proton-album", "proton-tag", "trash", "device", "proton-albums").forEach { stored ->
            assertEquals(
                GalleryDestination.Library,
                GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = stored)),
            )
        }
    }

    @Test
    fun `the retired combined timeline reopens as the Proton timeline`() {
        assertEquals(
            GalleryDestination.ProtonTimeline,
            GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "combined")),
        )
    }
}
