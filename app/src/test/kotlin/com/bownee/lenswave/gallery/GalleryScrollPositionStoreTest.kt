package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryScrollPositionStoreTest {
    @Test
    fun destinationsKeepIndependentScrollPositions() {
        val store = GalleryScrollPositionStore()
        val timeline = GalleryDestination.Timeline
        val albums = GalleryDestination.Library
        val favorites = GalleryDestination.Tag(ProtonMediaTag.FAVORITES)

        store.save(timeline, GalleryScrollPosition(42, -17))
        store.save(albums, GalleryScrollPosition(8, -3))
        store.save(favorites, GalleryScrollPosition(19, 4))

        assertEquals(GalleryScrollPosition(42, -17), store.positionFor(timeline))
        assertEquals(GalleryScrollPosition(8, -3), store.positionFor(albums))
        assertEquals(GalleryScrollPosition(19, 4), store.positionFor(favorites))
        assertNull(store.positionFor(GalleryDestination.Tag(ProtonMediaTag.VIDEOS)))
    }

    @Test
    fun individualAlbumsKeepIndependentScrollPositions() {
        val store = GalleryScrollPositionStore()
        val first = GalleryDestination.AlbumPhotos(ProtonAlbumReference("first", "First"))
        val second = GalleryDestination.AlbumPhotos(ProtonAlbumReference("second", "Second"))

        store.save(first, GalleryScrollPosition(12, -9))
        store.save(second, GalleryScrollPosition(27, -2))

        assertEquals(GalleryScrollPosition(12, -9), store.positionFor(first))
        assertEquals(GalleryScrollPosition(27, -2), store.positionFor(second))
    }

    @Test
    fun aPositionKeepsItsSpanAndFirstPhotoAndIsReplacedWhole() {
        val store = GalleryScrollPositionStore()
        val timeline = GalleryDestination.Timeline

        store.save(timeline, GalleryScrollPosition(42, -17, photoColumns = 3, firstVisibleAssetIndex = 9))
        assertEquals(
            GalleryScrollPosition(
                firstVisiblePosition = 42,
                topOffset = -17,
                photoColumns = 3,
                firstVisibleAssetIndex = 9,
            ),
            store.positionFor(timeline),
        )

        // A later save under another span replaces the whole position, unknown span and photo included.
        store.save(timeline, GalleryScrollPosition(4, 0))
        val replaced = store.positionFor(timeline)
        assertEquals(GalleryScrollPosition(4, 0), replaced)
        assertEquals(GalleryScrollPosition.UNKNOWN_COLUMNS, replaced?.photoColumns)
        assertNull(replaced?.firstVisibleAssetIndex)
    }
}
