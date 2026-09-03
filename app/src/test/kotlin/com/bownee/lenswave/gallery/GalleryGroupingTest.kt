package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class GalleryGroupingTest {
    @Test
    fun groupsNewestPhotosByDayAndChunksRows() {
        val photos = listOf(
            photo("jul-11", timestamp(2026, 7, 11)),
            photo("jul-12-d", timestamp(2026, 7, 12)),
            photo("jul-12-b", timestamp(2026, 7, 12)),
            photo("jul-12-a", timestamp(2026, 7, 12)),
            photo("jul-12-c", timestamp(2026, 7, 12)),
        )

        val rows = GalleryGrouping.createRows(
            photos,
            ZoneOffset.UTC,
            Locale.US,
            columns = 3,
            unknownDateLabel = "No capture date",
        )

        assertEquals("Sun, 12 Jul 2026", (rows[0] as GalleryRow.DateHeader).label)
        assertEquals(
            listOf("jul-12-a", "jul-12-b", "jul-12-c"),
            (rows[1] as GalleryRow.Photos).items.map { it.stableId },
        )
        assertEquals(listOf("jul-12-d"), (rows[2] as GalleryRow.Photos).items.map { it.stableId })
        assertEquals("Sat, 11 Jul 2026", (rows[3] as GalleryRow.DateHeader).label)
        assertEquals(listOf("jul-11"), (rows[4] as GalleryRow.Photos).items.map { it.stableId })
    }

    @Test
    fun placesPhotosWithoutDatesInASeparateFinalSection() {
        val rows = GalleryGrouping.createRows(
            listOf(photo("unknown", 0), photo("known", timestamp(2026, 3, 1))),
            ZoneId.of("UTC"),
            Locale.US,
            unknownDateLabel = "No capture date",
        )

        assertTrue(
            rows.last { it is GalleryRow.DateHeader } ==
                GalleryRow.DateHeader("unknown", "No capture date")
        )
    }

    @Test
    fun chunksAlbumsIntoTwoColumnRows() {
        val albums = (1..3).map { index -> album("album-$index") }

        val rows = GalleryGrouping.createAlbumRows(albums)

        assertEquals(listOf("album-1", "album-2"), (rows[0] as GalleryRow.Albums).items.map { it.nodeUid })
        assertEquals(listOf("album-3"), (rows[1] as GalleryRow.Albums).items.map { it.nodeUid })
    }

    private fun photo(id: String, timestamp: Long) = GalleryAsset.device(
        stableId = id,
        capturedAtEpochMillis = timestamp,
        displayName = id,
        uri = "content://media/$id",
        collection = DeviceCollection.CAMERA,
        sizeBytes = 1,
        modifiedAtEpochMillis = timestamp,
    )

    private fun timestamp(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun album(id: String) = ProtonAlbum(
        nodeUid = id,
        name = id,
        photoCount = 0,
        coverPhotoNodeUid = null,
        createdAtEpochSeconds = 0,
        lastActivityEpochSeconds = 0,
        hasCoverThumbnail = false,
        isShared = false,
    )
}
