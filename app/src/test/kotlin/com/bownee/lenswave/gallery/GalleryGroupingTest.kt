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
        val photos =
            listOf(
                photo("jul-11", timestamp(2026, 7, 11)),
                photo("jul-12-d", timestamp(2026, 7, 12)),
                photo("jul-12-b", timestamp(2026, 7, 12)),
                photo("jul-12-a", timestamp(2026, 7, 12)),
                photo("jul-12-c", timestamp(2026, 7, 12)),
            )

        val rows =
            GalleryGrouping.createRows(
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
        val rows =
            GalleryGrouping.createRows(
                listOf(photo("unknown", 0), photo("known", timestamp(2026, 3, 1))),
                ZoneId.of("UTC"),
                Locale.US,
                unknownDateLabel = "No capture date",
            )

        assertTrue(
            rows.last { it is GalleryRow.DateHeader } ==
                GalleryRow.DateHeader("unknown", "No capture date"),
        )
    }

    @Test
    fun libraryRowsHeadEachSectionAndChunkItemsIntoTwoColumns() {
        val albums = (1..3).map { index -> LibraryItem.Album(album("album-$index")) }
        val entries =
            (1..3).map { index ->
                LibraryItem.Entry(
                    key = "entry-$index",
                    label = "Entry $index",
                    iconRes = 0,
                    action = LibraryAction.Open(GalleryDestination.Library),
                )
            }

        val rows =
            GalleryGrouping.createLibraryRows(
                listOf(
                    LibrarySection("albums", "Albums", albums),
                    LibrarySection("device", "Device", entries),
                ),
            )

        assertEquals(GalleryRow.SectionHeading("albums", "Albums"), rows[0])
        assertEquals(listOf("album-1", "album-2"), (rows[1] as GalleryRow.Albums).items.map { it.nodeUid })
        assertEquals(listOf("album-3"), (rows[2] as GalleryRow.Albums).items.map { it.nodeUid })
        assertEquals(GalleryRow.SectionHeading("device", "Device"), rows[3])
        assertEquals(listOf("entry-1", "entry-2"), (rows[4] as GalleryRow.Entries).items.map { it.key })
        assertEquals(listOf("entry-3"), (rows[5] as GalleryRow.Entries).items.map { it.key })
        assertEquals(6, rows.size)
    }

    private fun photo(
        id: String,
        timestamp: Long,
    ) = GalleryAsset(
        stableId = id,
        capturedAtEpochMillis = timestamp,
        displayName = id,
        nodeUid = id,
        hasThumbnail = true,
    )

    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
    ): Long = LocalDateTime.of(year, month, day, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun album(id: String) =
        ProtonAlbum(
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
