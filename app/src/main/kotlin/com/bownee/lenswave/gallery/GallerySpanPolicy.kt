package com.bownee.lenswave.gallery

/**
 * How many photos a grid row holds, and how a scroll position survives that number changing.
 *
 * The count follows the list width: a cell aims at [TARGET_CELL_DP] and the row takes as many
 * whole cells as fit, never fewer than [MIN_COLUMNS] (the portrait phone grid) nor more than
 * [MAX_COLUMNS]. A rotation, a tablet or a resized multi-window pane therefore regroups the
 * rows instead of stretching three cells across the width.
 *
 * Row positions mean nothing across a change of span, so a saved position also carries the
 * index of the first visible photo in the page's flat asset list, and a restore into a grid of
 * another span lands on the row that now shows that photo.
 */
internal object GallerySpanPolicy {
    const val MIN_COLUMNS = 3
    const val MAX_COLUMNS = 6
    const val TARGET_CELL_DP = 120

    /** The column count for a list [listWidthPx] wide on a screen of [density] pixels per dp. */
    fun columns(
        listWidthPx: Int,
        density: Float,
    ): Int {
        if (listWidthPx <= 0 || density <= 0f) return MIN_COLUMNS
        val fitting = (listWidthPx / (TARGET_CELL_DP * density)).toInt()
        return fitting.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    /**
     * The flat asset index of the first photo shown at or below row [position]: the row's own
     * first photo, or, for a header, the first photo of the row under it. Null when no photo row
     * follows (a library page, or a position past the last row). A negative [position] (a list
     * header view is on top) starts from the first row.
     */
    fun firstAssetIndexAt(
        rows: List<GalleryRow>,
        position: Int,
    ): Int? {
        for (index in position.coerceAtLeast(0) until rows.size) {
            val row = rows[index]
            if (row is GalleryRow.Photos) return row.startIndex
        }
        return null
    }

    /**
     * The row showing the photo at [assetIndex], or the date header directly above it when the
     * photo opens its day, so a day the user had at the top keeps its heading. Null when no row
     * shows that photo.
     */
    fun positionOfAsset(
        rows: List<GalleryRow>,
        assetIndex: Int,
    ): Int? {
        for (index in rows.indices) {
            val row = rows[index] as? GalleryRow.Photos ?: continue
            if (assetIndex < row.startIndex || assetIndex >= row.startIndex + row.items.size) continue
            return if (index > 0 && rows[index - 1] is GalleryRow.DateHeader &&
                row.startIndex == assetIndex
            ) {
                index - 1
            } else {
                index
            }
        }
        return null
    }

    /**
     * Where a position saved under [saved]'s span lands in [rows] grouped with [photoColumns]:
     * the saved position itself when the span is unchanged or the photo it showed is unknown or
     * gone, otherwise the row that shows the photo, offset by the [headerViewCount] list header
     * views that precede the adapter's rows, aligned to its top.
     */
    fun restoredPosition(
        saved: GalleryScrollPosition,
        rows: List<GalleryRow>,
        photoColumns: Int,
        headerViewCount: Int,
    ): GalleryScrollPosition {
        if (saved.photoColumns == photoColumns) return saved
        val assetIndex = saved.firstVisibleAssetIndex ?: return saved
        val row = positionOfAsset(rows, assetIndex) ?: return saved
        return GalleryScrollPosition(
            firstVisiblePosition = row + headerViewCount,
            topOffset = 0,
            photoColumns = photoColumns,
            firstVisibleAssetIndex = assetIndex,
        )
    }
}
