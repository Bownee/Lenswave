package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailMarksTest {
    private val photoIndex = ProtonNodeUidIndex(ProtonGalleryPhoto::nodeUid)

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
    fun untouchedItemsKeepTheirInstanceAndTheSourceListIsLeftAlone() {
        val photos = List(5) { index -> photo("p$index", false) }

        val updated = photos.mark(setOf("p1", "p3", "absent"), available = true)

        assertNotNull(updated)
        updated!!
        assertEquals(5, updated.size)
        listOf(0, 2, 4).forEach { position -> assertSame(photos[position], updated[position]) }
        assertTrue(updated[1].hasThumbnail)
        assertTrue(updated[3].hasThumbnail)
        assertFalse(photos[1].hasThumbnail)
        assertFalse(photos[3].hasThumbnail)
    }

    @Test
    fun theIndexFollowsTheListItIsAskedAbout() {
        val first = listOf(photo("a", false), photo("b", false))
        val marked = first.mark(setOf("a"), available = true)!!
        // A new list with the same uids in the same positions keeps the memo, a different one does not.
        assertEquals(listOf(photo("a", true), photo("b", true)), marked.mark(setOf("b"), available = true))
        val reordered = listOf(photo("b", false), photo("a", false))
        assertEquals(listOf(photo("b", false), photo("a", true)), reordered.mark(setOf("a"), available = true))
    }

    @Test
    fun containsAnyNodeUidAnswersWithoutCopying() {
        val photos = listOf(photo("a", false), photo("b", false))

        assertTrue(photos.containsAnyNodeUid(setOf("x", "b"), photoIndex))
        assertFalse(photos.containsAnyNodeUid(setOf("x"), photoIndex))
        assertFalse(emptyList<ProtonGalleryPhoto>().containsAnyNodeUid(setOf("a"), photoIndex))
    }

    @Test
    fun itemsWithoutANodeUidAreLeftAlone() {
        val albums = listOf(album("with-cover", "cover", false), album("without-cover", null, false))

        val updated =
            albums.withThumbnailAvailability(
                setOf("cover"),
                available = true,
                index = ProtonNodeUidIndex(ProtonAlbum::coverPhotoNodeUid),
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
        photoIndex,
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
