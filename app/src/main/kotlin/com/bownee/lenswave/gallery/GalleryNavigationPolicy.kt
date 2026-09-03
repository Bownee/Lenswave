package com.bownee.lenswave.gallery

enum class GalleryTab {
    PHOTOS,
    LIBRARY,
}

object GalleryNavigationPolicy {
    fun tab(destination: GalleryDestination): GalleryTab = when (destination) {
        GalleryDestination.Timeline -> GalleryTab.PHOTOS
        else -> GalleryTab.LIBRARY
    }

    /** The screen Back returns to, or null when the destination is a tab root. */
    fun parent(destination: GalleryDestination): GalleryDestination? = when (destination) {
        GalleryDestination.Timeline,
        GalleryDestination.Library,
        -> null

        else -> GalleryDestination.Library
    }

    /** The tab root remembered across app restarts instead of a deep collection. */
    fun root(destination: GalleryDestination): GalleryDestination = when (tab(destination)) {
        GalleryTab.PHOTOS -> GalleryDestination.Timeline
        GalleryTab.LIBRARY -> GalleryDestination.Library
    }

    /** Where a destination lands once the account is gone: collections return to the Library. */
    fun withoutAccount(destination: GalleryDestination): GalleryDestination =
        parent(destination) ?: destination
}
