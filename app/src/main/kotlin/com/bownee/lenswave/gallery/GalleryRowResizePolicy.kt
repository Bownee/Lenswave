package com.bownee.lenswave.gallery

/**
 * How a recycled photo row is brought to the current span in place (see
 * [GalleryListAdapter]): which cells to drop or add at its end, and the gap each cell carries on
 * either side once the row has its new column count. Resizing the row it was handed keeps the
 * list's recycler consistent; a rejected row would go back on the scrap heap and be offered again
 * at the next bind, costing a full row inflation every time.
 */
internal object GalleryRowResizePolicy {
    /** Cells to remove from the row's end, then cells to append; at most one of them is non-zero. */
    data class Resize(
        val removeCount: Int,
        val addCount: Int,
    ) {
        val isNoOp: Boolean get() = removeCount == 0 && addCount == 0
    }

    /** The horizontal margins of one cell; the [gap] is split so neighbours share it exactly. */
    data class Margins(
        val start: Int,
        val end: Int,
    )

    fun resize(
        currentCells: Int,
        columns: Int,
    ): Resize =
        Resize(
            removeCount = (currentCells - columns).coerceAtLeast(0),
            addCount = (columns - currentCells).coerceAtLeast(0),
        )

    /**
     * The margins of the cell at [column] in a row of [columns]: nothing outside the first and
     * last cell, and the [gap] split between neighbours (the odd pixel goes to the trailing side).
     */
    fun cellMargins(
        column: Int,
        columns: Int,
        gap: Int,
    ): Margins =
        Margins(
            start = if (column > 0) gap / 2 else 0,
            end = if (column < columns - 1) gap - gap / 2 else 0,
        )
}
