package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    fun createRows(
        photos: List<GalleryAsset>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        columns: Int = 3,
        unknownDateLabel: String,
    ): List<GalleryRow> {
        require(columns > 0) { "Columns must be positive" }
        val sorted = sortPhotos(photos)
        val groups = sorted.groupBy { photo -> dateOf(photo, zoneId) }
        val formatter = DateTimeFormatter.ofPattern("EEE, d MMM uuuu", locale)
        return buildList {
            groups.forEach { (date, items) ->
                val label =
                    date?.format(formatter)?.replaceFirstChar { it.titlecase(locale) }
                        ?: unknownDateLabel
                add(GalleryRow.DateHeader(date?.toString() ?: "unknown", label))
                items.chunked(columns).forEach { add(GalleryRow.Photos(it)) }
            }
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

    private fun dateOf(
        photo: GalleryAsset,
        zoneId: ZoneId,
    ): LocalDate? =
        photo.capturedAtEpochMillis.takeIf { it > 0 }?.let { timestamp ->
            LocalDate.from(Instant.ofEpochMilli(timestamp).atZone(zoneId))
        }
}
