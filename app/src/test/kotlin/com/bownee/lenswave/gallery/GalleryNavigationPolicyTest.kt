package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryNavigationPolicyTest {
    @Test
    fun `destinations map to the three primary sections`() {
        assertEquals(GallerySection.PHOTOS, GalleryNavigationPolicy.section(GalleryDestination.Combined))
        assertEquals(
            GallerySection.PHOTOS,
            GalleryNavigationPolicy.section(GalleryDestination.ProtonTag(ProtonMediaTag.VIDEOS)),
        )
        assertEquals(
            GallerySection.ALBUMS,
            GalleryNavigationPolicy.section(GalleryDestination.ProtonAlbums),
        )
        assertEquals(
            GallerySection.ALBUMS,
            GalleryNavigationPolicy.section(
                GalleryDestination.ProtonAlbumPhotos(ProtonAlbumReference("album", "Album")),
            ),
        )
        assertEquals(
            GallerySection.TRASH,
            GalleryNavigationPolicy.section(GalleryDestination.Trash(PhotoSource.PROTON)),
        )
    }

    @Test
    fun `photo filters round trip through destinations`() {
        val filters = listOf(
            GalleryPhotoFilters(GallerySourceFilter.ALL),
            GalleryPhotoFilters(GallerySourceFilter.PROTON),
            GalleryPhotoFilters(GallerySourceFilter.PROTON, ProtonMediaTag.FAVORITES),
            GalleryPhotoFilters(
                GallerySourceFilter.DEVICE,
                deviceCollection = DeviceCollection.SCREENSHOTS,
            ),
        )

        filters.forEach { expected ->
            val destination = GalleryNavigationPolicy.photoDestination(expected)
            assertEquals(
                expected,
                GalleryNavigationPolicy.photoFilters(destination, expected.deviceCollection),
            )
        }
    }
}
