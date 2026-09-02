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
    fun groupsNewestPhotosByMonthAndChunksRows() {
        val photos = listOf(
            photo("feb-1", timestamp(2026, 2, 1)),
            photo("mar-1", timestamp(2026, 3, 1)),
            photo("mar-4", timestamp(2026, 3, 4)),
            photo("mar-2", timestamp(2026, 3, 2)),
            photo("mar-3", timestamp(2026, 3, 3)),
        )

        val rows = GalleryGrouping.createRows(
            photos,
            ZoneOffset.UTC,
            Locale.US,
            columns = 3,
            unknownDateLabel = "No capture date",
        )

        assertEquals("March 2026", (rows[0] as GalleryRow.MonthHeader).label)
        assertEquals(listOf("mar-4", "mar-3", "mar-2"), (rows[1] as GalleryRow.Photos).items.map { it.stableId })
        assertEquals(listOf("mar-1"), (rows[2] as GalleryRow.Photos).items.map { it.stableId })
        assertEquals("February 2026", (rows[3] as GalleryRow.MonthHeader).label)
        assertEquals(listOf("feb-1"), (rows[4] as GalleryRow.Photos).items.map { it.stableId })
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
            rows.last { it is GalleryRow.MonthHeader } ==
                GalleryRow.MonthHeader("unknown", "No capture date")
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
