package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtonThumbnailMarksTest {
    @Test
    fun marksOnlyTheRequestedPhotosAvailable() {
        val photos = listOf(photo("a", false), photo("b", false), photo("c", false))

        val updated = photos.mark(setOf("a", "c"), available = true)

        assertEquals(listOf(photo("a", true), photo("b", false), photo("c", true)), updated)
    }

    @Test
    fun marksOnlyTheRequestedPhotosUnavailable() {
        val photos = listOf(photo("a", true), photo("b", true))

        val updated = photos.mark(setOf("b"), available = false)

        assertEquals(listOf(photo("a", true), photo("b", false)), updated)
    }

    @Test
    fun returnsNullWhenNothingChanges() {
        val photos = listOf(photo("a", true), photo("b", false))

        assertNull(photos.mark(setOf("a"), available = true))
        assertNull(photos.mark(setOf("b"), available = false))
        assertNull(photos.mark(setOf("unknown"), available = true))
        assertNull(photos.mark(emptySet(), available = true))
        assertNull(emptyList<ProtonGalleryPhoto>().mark(setOf("a"), available = true))
    }

    @Test
    fun itemsWithoutANodeUidAreLeftAlone() {
        val albums = listOf(album("with-cover", "cover", false), album("without-cover", null, false))

        val updated =
            albums.withThumbnailAvailability(
                setOf("cover"),
                available = true,
                nodeUid = ProtonAlbum::coverPhotoNodeUid,
                hasThumbnail = ProtonAlbum::hasCoverThumbnail,
                copy = { album, hasCoverThumbnail -> album.copy(hasCoverThumbnail = hasCoverThumbnail) },
            )

        assertEquals(listOf(album("with-cover", "cover", true), album("without-cover", null, false)), updated)
    }

    private fun List<ProtonGalleryPhoto>.mark(
        nodeUids: Set<String>,
        available: Boolean,
    ) = withThumbnailAvailability(
        nodeUids,
        available,
        nodeUid = ProtonGalleryPhoto::nodeUid,
        hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
        copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
    )

    private fun photo(
        nodeUid: String,
        hasThumbnail: Boolean,
    ) = ProtonGalleryPhoto(nodeUid, captureTimeEpochSeconds = 1L, hasThumbnail = hasThumbnail)

    private fun album(
        nodeUid: String,
        coverPhotoNodeUid: String?,
        hasCoverThumbnail: Boolean,
    ) = ProtonAlbum(
        nodeUid = nodeUid,
        name = nodeUid,
        photoCount = 1L,
        coverPhotoNodeUid = coverPhotoNodeUid,
        createdAtEpochSeconds = 1L,
        lastActivityEpochSeconds = 1L,
        hasCoverThumbnail = hasCoverThumbnail,
        isShared = false,
    )
}
