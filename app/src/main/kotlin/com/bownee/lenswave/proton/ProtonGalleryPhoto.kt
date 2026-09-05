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
    /** See [ProtonGalleryState.listingRefused]. */
    val listingRefused: Boolean = false,
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
    /** The last refresh did not complete; cleared by the next publish of any kind. */
    val refreshFailed: Boolean = false,
    /**
     * An automatic refresh was refused because Proton's listing dropped a suspicious share of
     * the cached entries (see ProtonReconcileSafetyPolicy); the cached listing stays on screen
     * and is not what Proton has. Unlike [refreshFailed] it survives later automatic syncs and
     * their failures, and clears only when a listing is committed or a refresh the user asked
     * for (forceRemote) starts, since that refresh is trusted with the mass removal.
     */
    val listingRefused: Boolean = false,
    val tags: Map<ProtonMediaTag, ProtonTagState> = emptyMap(),
)
