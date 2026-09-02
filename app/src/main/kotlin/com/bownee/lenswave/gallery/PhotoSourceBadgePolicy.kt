package com.bownee.lenswave.gallery

object PhotoSourceBadgePolicy {
    fun shouldShow(destination: GalleryDestination, asset: GalleryAsset): Boolean =
        destination == GalleryDestination.Combined && asset.isStoredInProton
}
