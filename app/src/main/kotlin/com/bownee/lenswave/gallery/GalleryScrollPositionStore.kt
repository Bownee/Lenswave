package com.bownee.lenswave.gallery

/**
 * Where a page was scrolled to: a list position (header views included) plus the top offset of
 * that row. [photoColumns] is the span the rows were grouped with when the position was taken
 * and [firstVisibleAssetIndex] the flat index of the first photo on screen, so a restore into a
 * grid of another span can find the row that now shows it (see [GallerySpanPolicy]); both are
 * unknown for positions restored from an older saved state.
 */
internal data class GalleryScrollPosition(
    val firstVisiblePosition: Int,
    val topOffset: Int,
    val photoColumns: Int = UNKNOWN_COLUMNS,
    val firstVisibleAssetIndex: Int? = null,
) {
    companion object {
        const val UNKNOWN_COLUMNS = 0
    }
}

internal class GalleryScrollPositionStore {
    private val positions = mutableMapOf<GalleryDestination, GalleryScrollPosition>()

    fun save(
        destination: GalleryDestination,
        position: GalleryScrollPosition,
    ) {
        positions[destination] = position
    }

    fun positionFor(destination: GalleryDestination): GalleryScrollPosition? = positions[destination]
}
