package com.bownee.lenswave.gallery

internal data class GalleryScrollPosition(
    val firstVisiblePosition: Int,
    val topOffset: Int,
)

internal class GalleryScrollPositionStore {
    private val positions = mutableMapOf<GalleryDestination, GalleryScrollPosition>()

    fun save(destination: GalleryDestination, position: GalleryScrollPosition) {
        positions[destination] = position
    }

    fun positionFor(destination: GalleryDestination): GalleryScrollPosition? = positions[destination]
}
