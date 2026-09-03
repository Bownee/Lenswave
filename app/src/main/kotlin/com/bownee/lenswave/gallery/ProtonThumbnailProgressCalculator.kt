package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonGalleryPhoto

internal data class ProtonThumbnailProgress(
    val downloaded: Int,
    val total: Int,
)

internal object ProtonThumbnailProgressCalculator {
    fun timeline(photos: List<ProtonGalleryPhoto>) = ProtonThumbnailProgress(
        downloaded = photos.count(ProtonGalleryPhoto::hasThumbnail),
        total = photos.size,
    )

    fun albumCovers(albums: List<ProtonAlbum>) = ProtonThumbnailProgress(
        downloaded = albums.count { album ->
            album.coverPhotoNodeUid != null && album.hasCoverThumbnail
        },
        total = albums.count { album -> album.coverPhotoNodeUid != null },
    )
}
