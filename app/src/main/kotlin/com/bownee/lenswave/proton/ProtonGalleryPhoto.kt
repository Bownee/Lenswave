package com.bownee.lenswave.proton

data class ProtonGalleryPhoto(
    val nodeUid: String,
    val captureTimeEpochSeconds: Long,
    val hasThumbnail: Boolean,
)

data class ProtonGalleryState(
    val userId: String? = null,
    val photos: List<ProtonGalleryPhoto> = emptyList(),
    val syncing: Boolean = false,
    val downloadedThumbnailCount: Int = 0,
    val errorMessage: String? = null,
)
