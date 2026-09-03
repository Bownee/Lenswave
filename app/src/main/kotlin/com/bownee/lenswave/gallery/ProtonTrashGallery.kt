package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTrashPhoto

object ProtonTrashGallery {
    fun createPhotos(
        photos: List<ProtonTrashPhoto>,
        videoNodeUids: Set<String> = emptySet(),
        favoriteNodeUids: Set<String> = emptySet(),
    ): List<GalleryAsset> = photos.map { photo ->
        GalleryAsset(
            stableId = "proton-trash:${photo.nodeUid}",
            capturedAtEpochMillis = photo.trashedAtEpochSeconds * 1_000L,
            displayName = photo.displayName,
            nodeUid = photo.nodeUid,
            hasThumbnail = photo.hasThumbnail,
            mediaKind = if (photo.mediaKind == MediaKind.VIDEO || photo.nodeUid in videoNodeUids) {
                MediaKind.VIDEO
            } else {
                MediaKind.IMAGE
            },
            tags = if (photo.nodeUid in favoriteNodeUids) {
                setOf(ProtonMediaTag.FAVORITES)
            } else {
                emptySet()
            },
            isTrashed = true,
        )
    }
}
