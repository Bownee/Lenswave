package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneRules
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface GalleryRow {
    data class DateHeader(
        val key: String,
        val label: String,
    ) : GalleryRow

    data class SectionHeading(
        val key: String,
        val label: String,
    ) : GalleryRow

    data class Photos(
        val items: List<GalleryAsset>,
        /** Index of `items[0]` in the page's flat asset list, so a tap knows its position without a search. */
        val startIndex: Int = 0,
    ) : GalleryRow

    data class Albums(
        val items: List<ProtonAlbum>,
    ) : GalleryRow

    data class Entries(
        val items: List<LibraryItem.Entry>,
    ) : GalleryRow
}

/** Finished list rows plus the per-row sticky date label, built off the main thread for the adapter. */
class GalleryRowSet(
    val rows: List<GalleryRow>,
    val dateLabels: List<String?>,
) {
    companion object {
        val EMPTY = GalleryRowSet(emptyList(), emptyList())

        fun of(rows: List<GalleryRow>) = GalleryRowSet(rows, GalleryGrouping.dateLabels(rows))
    }
}

object GalleryGrouping {
    /**
     * Newest first, undated photos last, ties broken by id. The comparator works on primitive
     * longs so sorting a long timeline allocates nothing per comparison.
     */
    private val newestFirst =
        Comparator<GalleryAsset> { first, second ->
            val byTime = sortTime(second).compareTo(sortTime(first))
            if (byTime != 0) byTime else first.stableId.compareTo(second.stableId)
        }

    fun sortPhotos(photos: List<GalleryAsset>): List<GalleryAsset> = photos.sortedWith(newestFirst)

    /** Undated photos (no positive capture time) sort below every dated one. */
    private fun sortTime(photo: GalleryAsset): Long =
        if (photo.capturedAtEpochMillis > 0) photo.capturedAtEpochMillis else Long.MIN_VALUE

    /**
     * Groups [photos] into a date header followed by rows of [columns] photos per day.
     *
     * [photos] must already be in display order (see [sortPhotos]), which keeps every day's
     * photos contiguous, so a day group is a run of the list: the grouping walks it once with a
     * running day and allocates neither a map nor a boxed key per photo. Days are bucketed with
     * integer arithmetic on the epoch millis and the zone offset in force at each instant, which
     * is what `LocalDate.from(instant.atZone(zoneId))` yields, and each day's label comes from
     * [dayLabels], which formats a day once and answers from its cache afterwards. Every photo
     * row carries the index of its first photo in [photos].
     */
    fun createRows(
        photos: List<GalleryAsset>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        columns: Int = 3,
        unknownDateLabel: String,
        dayLabels: DayLabels = DayLabels(locale),
    ): List<GalleryRow> {
        require(columns > 0) { "Columns must be positive" }
        if (photos.isEmpty()) return emptyList()
        val offsets = ZoneOffsetLookup(zoneId.rules)
        val rows = ArrayList<GalleryRow>(photos.size / columns + ROW_CAPACITY_HEADROOM)
        var currentDay = 0L
        var runStart = 0
        photos.forEachIndexed { index, photo ->
            val millis = photo.capturedAtEpochMillis
            val day = epochDay(millis, offsets.offsetSeconds(millis))
            if (index == 0 || day != currentDay) {
                addPhotoRows(rows, photos, runStart, index, columns)
                rows.add(dayHeader(day, unknownDateLabel, dayLabels))
                currentDay = day
                runStart = index
            }
        }
        addPhotoRows(rows, photos, runStart, photos.size, columns)
        return rows
    }

    private fun dayHeader(
        day: Long,
        unknownDateLabel: String,
        dayLabels: DayLabels,
    ): GalleryRow.DateHeader =
        if (day == UNKNOWN_DAY) {
            GalleryRow.DateHeader("unknown", unknownDateLabel)
        } else {
            GalleryRow.DateHeader(dayLabels.key(day), dayLabels.label(day))
        }

    /** Chunks the photos of one day, `[start, end)` in [photos], into rows of [columns]. */
    private fun addPhotoRows(
        rows: MutableList<GalleryRow>,
        photos: List<GalleryAsset>,
        start: Int,
        end: Int,
        columns: Int,
    ) {
        var rowStart = start
        while (rowStart < end) {
            val rowEnd = minOf(rowStart + columns, end)
            rows.add(GalleryRow.Photos(photos.subList(rowStart, rowEnd), startIndex = rowStart))
            rowStart = rowEnd
        }
    }

    /**
     * Day header labels by epoch day. Formatting through java.time costs a calendar date, a
     * formatter pass and a titlecase per day, so the label is built once per day and reused
     * across renders; the owner drops the cache when the locale changes. Renders may overlap
     * (a cancelled row build on a worker thread and its successor), hence the concurrent map.
     */
    class DayLabels(
        private val locale: Locale = Locale.getDefault(),
    ) {
        private val formatter = DateTimeFormatter.ofPattern("EEE, d MMM uuuu", locale)
        private val labels = ConcurrentHashMap<Long, String>()
        private val keys = ConcurrentHashMap<Long, String>()

        fun label(day: Long): String =
            labels.getOrPut(day) {
                LocalDate.ofEpochDay(day).format(formatter).replaceFirstChar { it.titlecase(locale) }
            }

        /** The ISO date, a locale-independent header key. */
        fun key(day: Long): String = keys.getOrPut(day) { LocalDate.ofEpochDay(day).toString() }
    }

    /**
     * The local calendar day of [epochMillis] as days since 1970-01-01, given the zone offset in
     * force at that instant; [UNKNOWN_DAY] for photos without a capture time.
     */
    fun epochDay(
        epochMillis: Long,
        offsetSeconds: Int,
    ): Long =
        if (epochMillis > 0) {
            Math.floorDiv(epochMillis + offsetSeconds * MILLIS_PER_SECOND, MILLIS_PER_DAY)
        } else {
            UNKNOWN_DAY
        }

    /**
     * For every row, the label of the nearest date header at or above it (null before the first
     * header), so a sticky date lookup is an array read instead of a backward scan.
     */
    fun dateLabels(rows: List<GalleryRow>): List<String?> {
        var label: String? = null
        return rows.map { row ->
            if (row is GalleryRow.DateHeader) label = row.label
            label
        }
    }

    fun createLibraryRows(
        sections: List<LibrarySection>,
        albumColumns: Int = 2,
        entryColumns: Int = 2,
    ): List<GalleryRow> {
        require(albumColumns > 0 && entryColumns > 0) { "Columns must be positive" }
        return buildList {
            sections.forEach { section ->
                if (section.title.isNotEmpty()) add(GalleryRow.SectionHeading(section.key, section.title))
                section.items
                    .filterIsInstance<LibraryItem.Album>()
                    .map(LibraryItem.Album::album)
                    .chunked(albumColumns)
                    .forEach { add(GalleryRow.Albums(it)) }
                section.items
                    .filterIsInstance<LibraryItem.Entry>()
                    .chunked(entryColumns)
                    .forEach { add(GalleryRow.Entries(it)) }
            }
        }
    }

    /**
     * The zone offset in force at an instant, remembered together with the transition window it
     * holds for. Photos arrive sorted, so consecutive lookups fall in the same window and the
     * rules (a binary search plus allocations per call in zones with daylight saving) are only
     * consulted when an instant leaves it. A fixed-offset zone has no transitions, so its single
     * window spans every instant.
     */
    internal class ZoneOffsetLookup(
        private val rules: ZoneRules,
    ) {
        /** The window [windowStartMillis, windowEndMillis) that [offsetSeconds] holds for; empty until the first lookup. */
        var windowStartMillis = Long.MAX_VALUE
            private set
        var windowEndMillis = Long.MIN_VALUE
            private set
        private var offsetSeconds = 0

        fun offsetSeconds(epochMillis: Long): Int {
            if (epochMillis < windowStartMillis || epochMillis >= windowEndMillis) refresh(epochMillis)
            return offsetSeconds
        }

        private fun refresh(epochMillis: Long) {
            // Transitions fall on whole seconds, so the window is bounded in seconds: the last
            // transition at or before this second (previousTransition is exclusive, hence +1) and
            // the first one after it (nextTransition is exclusive too).
            val epochSecond = Math.floorDiv(epochMillis, MILLIS_PER_SECOND)
            val instant = Instant.ofEpochSecond(epochSecond)
            offsetSeconds = rules.getOffset(instant).totalSeconds
            windowStartMillis =
                rules.previousTransition(Instant.ofEpochSecond(epochSecond + 1))?.instant?.toEpochMilli()
                    ?: Long.MIN_VALUE
            windowEndMillis = rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
        }
    }

    const val UNKNOWN_DAY = Long.MIN_VALUE
    private const val ROW_CAPACITY_HEADROOM = 16
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_DAY = 86_400_000L
}
