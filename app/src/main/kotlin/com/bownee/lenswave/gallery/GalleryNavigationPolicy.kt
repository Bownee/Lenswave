package com.bownee.lenswave.gallery

enum class GalleryTab {
    PHOTOS,
    LIBRARY,
}

object GalleryNavigationPolicy {
    fun tab(destination: GalleryDestination): GalleryTab = when (destination) {
        GalleryDestination.ProtonTimeline -> GalleryTab.PHOTOS
        else -> GalleryTab.LIBRARY
    }

    /** The screen Back returns to, or null when the destination is a tab root. */
    fun parent(destination: GalleryDestination): GalleryDestination? = when (destination) {
        GalleryDestination.ProtonTimeline,
        GalleryDestination.Library,
        -> null

        else -> GalleryDestination.Library
    }

    /** The tab root remembered across app restarts instead of a deep collection. */
    fun root(destination: GalleryDestination): GalleryDestination = when (tab(destination)) {
        GalleryTab.PHOTOS -> GalleryDestination.ProtonTimeline
        GalleryTab.LIBRARY -> GalleryDestination.Library
    }

    fun requiresProton(destination: GalleryDestination): Boolean = when (destination) {
        GalleryDestination.ProtonTimeline,
        is GalleryDestination.ProtonTag,
        is GalleryDestination.ProtonAlbumPhotos,
        -> true

        is GalleryDestination.Trash -> destination.source == PhotoSource.PROTON
        GalleryDestination.Library,
        is GalleryDestination.Device,
        -> false
    }

    /**
     * Where a destination lands once Proton is no longer connected. The Photos tab stays put and
     * shows the connect prompt; Proton collections return to the Library.
     */
    fun withoutProton(destination: GalleryDestination): GalleryDestination =
        if (requiresProton(destination) && tab(destination) == GalleryTab.LIBRARY) {
            GalleryDestination.Library
        } else {
            destination
        }
}
