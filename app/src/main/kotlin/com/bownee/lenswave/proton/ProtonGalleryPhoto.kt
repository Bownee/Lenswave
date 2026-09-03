package com.bownee.lenswave.proton

data class ProtonGalleryPhoto(
    val nodeUid: String,
    val captureTimeEpochSeconds: Long,
    val hasThumbnail: Boolean,
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

enum class ProtonThumbnailWorkIssue {
    TIMEOUT,
    INCOMPLETE,
    ERROR,
}

sealed interface ProtonThumbnailWorkStatus {
    val attempt: Int
    val maximumAttempts: Int

    data class Running(
        override val attempt: Int,
        override val maximumAttempts: Int,
    ) : ProtonThumbnailWorkStatus

    data class RetryScheduled(
        override val attempt: Int,
        override val maximumAttempts: Int,
        val issue: ProtonThumbnailWorkIssue,
    ) : ProtonThumbnailWorkStatus

    data class Stopped(
        override val attempt: Int,
        override val maximumAttempts: Int,
        val issue: ProtonThumbnailWorkIssue,
    ) : ProtonThumbnailWorkStatus
}

data class ProtonGalleryState(
    val userId: String? = null,
    val photos: List<ProtonGalleryPhoto> = emptyList(),
    val hasLoaded: Boolean = false,
    val syncing: Boolean = false,
    val refreshFailed: Boolean = false,
    val thumbnailWorkStatus: ProtonThumbnailWorkStatus? = null,
    val tags: Map<ProtonMediaTag, ProtonTagState> = emptyMap(),
)
