package com.bownee.lenswave.proton

data class ProtonGalleryPhoto(
    val nodeUid: String,
    val captureTimeEpochSeconds: Long,
    val hasThumbnail: Boolean,
    /** A screen-sized preview is stored on the device; hydrated from disk, never persisted. */
    val hasPreview: Boolean = false,
)

data class ProtonTagState(
    val photos: List<ProtonGalleryPhoto> = emptyList(),
    val hasLoaded: Boolean = false,
    val syncing: Boolean = false,
    val refreshFailed: Boolean = false,
)

data class ProtonFavoriteResult(
    val updatedCount: Int = 0,
    val failedCount: Int = 0,
)

data class ProtonTrashResult(
    val trashedCount: Int = 0,
    val failedCount: Int = 0,
)

data class ProtonGalleryState(
    val userId: String? = null,
    val photos: List<ProtonGalleryPhoto> = emptyList(),
    val hasLoaded: Boolean = false,
    val syncing: Boolean = false,
    val refreshFailed: Boolean = false,
    val tags: Map<ProtonMediaTag, ProtonTagState> = emptyMap(),
)
