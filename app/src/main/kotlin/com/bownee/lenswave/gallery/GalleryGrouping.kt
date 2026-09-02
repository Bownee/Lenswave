package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface GalleryRow {
    data class MonthHeader(val key: String, val label: String) : GalleryRow
    data class Photos(val items: List<GalleryAsset>) : GalleryRow
    data class Albums(val items: List<ProtonAlbum>) : GalleryRow
}

object GalleryGrouping {
    fun sortPhotos(photos: List<GalleryAsset>): List<GalleryAsset> = photos.sortedWith(
        compareByDescending<GalleryAsset> { it.capturedAtEpochMillis > 0 }
            .thenByDescending { it.capturedAtEpochMillis }
            .thenBy { it.stableId }
    )

    fun createRows(
        photos: List<GalleryAsset>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        columns: Int = 3,
        unknownDateLabel: String,
    ): List<GalleryRow> {
        require(columns > 0) { "Columns must be positive" }
        val sorted = sortPhotos(photos)
        val groups = sorted.groupBy { photo -> monthOf(photo, zoneId) }
        val formatter = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        return buildList {
            groups.forEach { (month, items) ->
                val label = month?.format(formatter)?.replaceFirstChar { it.titlecase(locale) }
                    ?: unknownDateLabel
                add(GalleryRow.MonthHeader(month?.toString() ?: "unknown", label))
                items.chunked(columns).forEach { add(GalleryRow.Photos(it)) }
            }
        }
    }

    fun createAlbumRows(albums: List<ProtonAlbum>, columns: Int = 2): List<GalleryRow> {
        require(columns > 0) { "Columns must be positive" }
        return albums.chunked(columns).map(GalleryRow::Albums)
    }

    private fun monthOf(photo: GalleryAsset, zoneId: ZoneId): YearMonth? =
        photo.capturedAtEpochMillis.takeIf { it > 0 }?.let { timestamp ->
            YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(zoneId))
        }

}
