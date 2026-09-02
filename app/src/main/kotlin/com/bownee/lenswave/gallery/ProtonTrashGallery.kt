package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonTrashPhoto

object ProtonTrashGallery {
    fun createPhotos(photos: List<ProtonTrashPhoto>): List<GalleryAsset> = photos.map { photo ->
        GalleryAsset.proton(
            stableId = "proton-trash:${photo.nodeUid}",
            capturedAtEpochMillis = photo.trashedAtEpochSeconds * 1_000L,
            displayName = photo.displayName,
            nodeUid = photo.nodeUid,
            hasThumbnail = photo.hasThumbnail,
            isTrashed = true,
        )
    }
}
