package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.MediaKind

data class ProtonTrashPhoto(
    val nodeUid: String,
    val trashedAtEpochSeconds: Long,
    val hasThumbnail: Boolean,
    val displayName: String = "",
    val captureTimeEpochSeconds: Long = trashedAtEpochSeconds,
    val mediaKind: MediaKind = MediaKind.IMAGE,
)

data class ProtonTrashState(
    val userId: String? = null,
    val photos: List<ProtonTrashPhoto> = emptyList(),
    val hasLoaded: Boolean = false,
    val syncing: Boolean = false,
    val refreshFailed: Boolean = false,
)

data class ProtonTrashResult(
    val trashedCount: Int = 0,
    val failedCount: Int = 0,
)

data class ProtonDeleteResult(
    val deletedCount: Int = 0,
    val failedCount: Int = 0,
)
