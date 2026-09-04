package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneRules
import java.util.Locale

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
     * [photos] must already be in display order (see [sortPhotos]); the grouping keeps that order
     * and merges any later photos of an already-seen day into that day's rows, so it never needs
     * to sort. Days are bucketed with integer arithmetic on the epoch millis and the zone offset
     * in force at each instant, which is what `LocalDate.from(instant.atZone(zoneId))` yields,
     * and the calendar date and its label are only built once per day group.
     */
    fun createRows(
        photos: List<GalleryAsset>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        columns: Int = 3,
        unknownDateLabel: String,
    ): List<GalleryRow> {
        require(columns > 0) { "Columns must be positive" }
        if (photos.isEmpty()) return emptyList()
        val groups = LinkedHashMap<Long, ArrayList<GalleryAsset>>()
        val offsets = ZoneOffsetLookup(zoneId.rules)
        photos.forEach { photo ->
            val millis = photo.capturedAtEpochMillis
            groups.getOrPut(epochDay(millis, offsets.offsetSeconds(millis)), ::ArrayList).add(photo)
        }
        val formatter = DateTimeFormatter.ofPattern("EEE, d MMM uuuu", locale)
        val rows = ArrayList<GalleryRow>(groups.size + photos.size / columns + groups.size)
        groups.forEach { (day, items) ->
            if (day == UNKNOWN_DAY) {
                rows.add(GalleryRow.DateHeader("unknown", unknownDateLabel))
            } else {
                val date = LocalDate.ofEpochDay(day)
                val label = date.format(formatter).replaceFirstChar { it.titlecase(locale) }
                rows.add(GalleryRow.DateHeader(date.toString(), label))
            }
            var start = 0
            while (start < items.size) {
                val end = minOf(start + columns, items.size)
                rows.add(GalleryRow.Photos(items.subList(start, end)))
                start = end
            }
        }
        return rows
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
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_DAY = 86_400_000L
}
