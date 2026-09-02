package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonTrashPhoto

internal data class ProtonThumbnailProgress(
    val downloaded: Int,
    val total: Int,
)

internal object ProtonThumbnailProgressCalculator {
    fun calculate(
        timeline: List<ProtonGalleryPhoto>,
        albums: List<ProtonAlbum>,
        trash: List<ProtonTrashPhoto>,
    ): ProtonThumbnailProgress {
        val availability = linkedMapOf<String, Boolean>()
        timeline.forEach { photo -> availability.merge(photo.nodeUid, photo.hasThumbnail, Boolean::or) }
        albums.forEach { album ->
            album.coverPhotoNodeUid?.let { nodeUid ->
                availability.merge(nodeUid, album.hasCoverThumbnail, Boolean::or)
            }
        }
        trash.forEach { photo -> availability.merge(photo.nodeUid, photo.hasThumbnail, Boolean::or) }
        return ProtonThumbnailProgress(
            downloaded = availability.values.count { it },
            total = availability.size,
        )
    }
}
