package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag

/**
 * Every screen the gallery can show. The Photos tab is the Proton timeline; every other
 * destination is a collection reached from the Library tab. See [GalleryNavigationPolicy].
 */
sealed interface GalleryDestination {
    data object ProtonTimeline : GalleryDestination

    data object Library : GalleryDestination

    data class Device(
        val collection: DeviceCollection,
    ) : GalleryDestination

    data class ProtonTag(
        val tag: ProtonMediaTag,
    ) : GalleryDestination

    data class ProtonAlbumPhotos(
        val album: ProtonAlbumReference,
    ) : GalleryDestination

    data class Trash(
        val source: PhotoSource,
    ) : GalleryDestination
}
