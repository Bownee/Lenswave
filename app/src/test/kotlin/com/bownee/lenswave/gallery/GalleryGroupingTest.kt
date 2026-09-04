package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
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
                GalleryGrouping.sortPhotos(photos),
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
        assertEquals(listOf("2026-07-12", "2026-07-11"), rows.filterIsInstance<GalleryRow.DateHeader>().map { it.key })
    }

    @Test
    fun keepsTheGivenOrderAndMergesLaterPhotosOfADayAlreadySeen() {
        val rows =
            GalleryGrouping.createRows(
                listOf(
                    photo("b", timestamp(2026, 7, 12)),
                    photo("a", timestamp(2026, 7, 11)),
                    photo("c", timestamp(2026, 7, 12)),
                ),
                ZoneOffset.UTC,
                Locale.US,
                unknownDateLabel = "No capture date",
            )

        assertEquals(listOf("b", "c"), (rows[1] as GalleryRow.Photos).items.map { it.stableId })
        assertEquals(listOf("a"), (rows[3] as GalleryRow.Photos).items.map { it.stableId })
        assertEquals(4, rows.size)
    }

    @Test
    fun placesPhotosWithoutDatesInASeparateFinalSection() {
        val rows =
            GalleryGrouping.createRows(
                listOf(photo("known", timestamp(2026, 3, 1)), photo("unknown", 0)),
                ZoneId.of("UTC"),
                Locale.US,
                unknownDateLabel = "No capture date",
            )

        assertTrue(
            rows.last { it is GalleryRow.DateHeader } ==
                GalleryRow.DateHeader("unknown", "No capture date"),
        )
        assertEquals(emptyList<GalleryRow>(), GalleryGrouping.createRows(emptyList(), unknownDateLabel = "-"))
    }

    @Test
    fun dayBucketsMatchTheZonedCalendarDateAroundDaylightSavingTransitions() {
        val zones =
            listOf(
                "Europe/Zurich",
                "America/Sao_Paulo",
                "America/Santiago",
                "Pacific/Apia",
                "Asia/Kathmandu",
                "Australia/Lord_Howe",
                "UTC",
            ).map(ZoneId::of)
        val interesting =
            listOf(
                Instant.parse("2026-03-29T00:59:59Z"),
                Instant.parse("2026-03-29T01:00:00Z"),
                Instant.parse("2026-10-25T00:59:59Z"),
                Instant.parse("2026-10-25T01:00:00Z"),
                Instant.parse("2018-11-04T02:59:59Z"),
                Instant.parse("2018-11-04T03:00:00Z"),
                Instant.parse("2019-02-17T01:59:59Z"),
                Instant.parse("2019-02-17T02:00:00Z"),
                Instant.parse("2011-12-30T09:59:59Z"),
                Instant.parse("2011-12-30T10:00:00Z"),
                Instant.parse("1970-01-01T00:00:00.001Z"),
                Instant.parse("1969-12-31T23:59:59.999Z"),
            )
        zones.forEach { zone ->
            val rules = zone.rules
            // A sweep at an odd stride crosses every transition of two years at varied times of day.
            val samples =
                interesting +
                    generateSequence(Instant.parse("2025-01-01T00:00:00Z")) { it.plusSeconds(7 * 3_600 + 1_801) }
                        .take(2_600)
                        .toList()
            samples.forEach { instant ->
                val millis = instant.toEpochMilli()
                if (millis <= 0) return@forEach
                val expected = LocalDate.from(instant.atZone(zone))
                val actual =
                    LocalDate.ofEpochDay(
                        GalleryGrouping.epochDay(millis, rules.getOffset(instant).totalSeconds),
                    )
                assertEquals("$zone at $instant", expected, actual)
            }
        }
    }

    @Test
    fun undatedPhotosGetTheUnknownDayBucket() {
        assertEquals(GalleryGrouping.UNKNOWN_DAY, GalleryGrouping.epochDay(0, 3_600))
        assertEquals(GalleryGrouping.UNKNOWN_DAY, GalleryGrouping.epochDay(-5, 0))
        assertEquals(0L, GalleryGrouping.epochDay(1, 0))
        assertEquals(-1L, GalleryGrouping.epochDay(1, -3_600))
    }

    @Test
    fun dateLabelsCarryEachHeadingDownToItsRows() {
        val rows =
            GalleryGrouping.createRows(
                listOf(
                    photo("x", timestamp(2026, 7, 12)),
                    photo("y", timestamp(2026, 7, 12)),
                    photo("z", timestamp(2026, 7, 11)),
                ),
                ZoneOffset.UTC,
                Locale.US,
                columns = 1,
                unknownDateLabel = "-",
            )

        assertEquals(
            listOf("Sun, 12 Jul 2026", "Sun, 12 Jul 2026", "Sun, 12 Jul 2026", "Sat, 11 Jul 2026", "Sat, 11 Jul 2026"),
            GalleryGrouping.dateLabels(rows),
        )
        assertEquals(
            listOf(null, "A"),
            GalleryGrouping.dateLabels(listOf(GalleryRow.Photos(emptyList()), GalleryRow.DateHeader("a", "A"))),
        )
        assertTrue(GalleryRowSet.EMPTY.rows.isEmpty())
        assertEquals(rows, GalleryRowSet.of(rows).rows)
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
