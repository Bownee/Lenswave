package com.bownee.lenswave.proton

data class ProtonGalleryPhoto(
    val nodeUid: String,
    val captureTimeEpochSeconds: Long,
    val hasThumbnail: Boolean,
)

enum class ProtonThumbnailWorkIssue {
    TIMEOUT,
    INCOMPLETE,
    INTERRUPTED,
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
    val syncing: Boolean = false,
    val downloadedThumbnailCount: Int = 0,
    val errorMessage: String? = null,
    val thumbnailWorkStatus: ProtonThumbnailWorkStatus? = null,
)
