package com.bownee.lenswave.gallery

enum class GalleryTab {
    PHOTOS,
    ALBUMS,
}

/**
 * How destinations map onto the two top-level tabs. The Photos tab shows the timeline or one
 * media-type filter of it; the Albums tab lists albums and opens them as sub-pages.
 */
object GalleryNavigationPolicy {
    fun tab(destination: GalleryDestination): GalleryTab =
        when (destination) {
            GalleryDestination.Timeline,
            is GalleryDestination.Tag,
            -> GalleryTab.PHOTOS

            else -> GalleryTab.ALBUMS
        }

    /** The screen Back returns to, or null when the destination is a tab root. */
    fun parent(destination: GalleryDestination): GalleryDestination? =
        when (destination) {
            GalleryDestination.Timeline,
            GalleryDestination.Library,
            -> null

            is GalleryDestination.Tag -> GalleryDestination.Timeline

            else -> GalleryDestination.Library
        }

    /** Sub-pages of the Albums tab replace the tab switch with a back button and a title. */
    fun showsBack(destination: GalleryDestination): Boolean = destination is GalleryDestination.AlbumPhotos

    /** Media-type filter chips belong to the Photos tab only. */
    fun showsFilters(destination: GalleryDestination): Boolean = tab(destination) == GalleryTab.PHOTOS

    /** The tab root remembered across app restarts instead of a deep collection. */
    fun root(destination: GalleryDestination): GalleryDestination =
        when (tab(destination)) {
            GalleryTab.PHOTOS -> GalleryDestination.Timeline
            GalleryTab.ALBUMS -> GalleryDestination.Library
        }

    /** Where a destination lands once the account is gone: collections return to their tab root. */
    fun withoutAccount(destination: GalleryDestination): GalleryDestination = parent(destination) ?: destination

    /**
     * Where an album page goes once the album is gone, or null when it stays. The data layer does
     * not distinguish an absent album from an empty one, so absence is "not in the album list once
     * that list has loaded": a restored page of a deleted album would otherwise show "album is
     * empty" under a stale name forever.
     */
    fun withoutAlbum(
        destination: GalleryDestination,
        albumsLoaded: Boolean,
        hasAlbum: (nodeUid: String) -> Boolean,
    ): GalleryDestination? {
        if (destination !is GalleryDestination.AlbumPhotos || !albumsLoaded) return null
        if (hasAlbum(destination.album.nodeUid)) return null
        return parent(destination)
    }
}
