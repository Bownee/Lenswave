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
            GalleryDestination.Device(),
            GalleryDestination.Device(DeviceCollection.SCREENSHOTS),
            GalleryDestination.ProtonTimeline,
            GalleryDestination.ProtonTag(ProtonMediaTag.FAVORITES),
            GalleryDestination.Library,
            GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("album-id", "Favorites")),
            GalleryDestination.Trash(PhotoSource.DEVICE),
            GalleryDestination.Trash(PhotoSource.PROTON),
        )

        destinations.forEach { destination ->
            val state = GalleryNavigationState(destination, GallerySource.PROTON)

            assertEquals(state, GalleryNavigationCodec.decode(GalleryNavigationCodec.encode(state)))
        }
    }

    @Test
    fun `missing or unknown destination is not treated as a previous screen`() {
        assertNull(GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = null)))
        assertNull(GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "unknown")))
    }

    @Test
    fun `incomplete nested screens fall back to the Library`() {
        listOf("proton-album", "proton-tag", "trash", "proton-albums").forEach { stored ->
            assertEquals(
                GalleryNavigationState(GalleryDestination.Library),
                GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = stored)),
            )
        }
    }

    @Test
    fun `missing source is inferred from a timeline and otherwise defaults to all`() {
        assertEquals(
            GalleryNavigationState(GalleryDestination.Device(), GallerySource.DEVICE),
            GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "device")),
        )
        assertEquals(
            GalleryNavigationState(GalleryDestination.Library, GallerySource.ALL),
            GalleryNavigationCodec.decode(StoredGalleryNavigation(destination = "library", source = "bogus")),
        )
    }
}
