package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag

/**
 * Every screen the gallery can show. The Photos tab is the timeline; every other destination is
 * a collection reached from the Library tab. See [GalleryNavigationPolicy].
 */
sealed interface GalleryDestination {
    data object Timeline : GalleryDestination

    data object Library : GalleryDestination

    data class Tag(
        val tag: ProtonMediaTag,
    ) : GalleryDestination

    data class AlbumPhotos(
        val album: ProtonAlbumReference,
    ) : GalleryDestination
}
