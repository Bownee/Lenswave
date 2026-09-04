package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonMediaTag

enum class MediaKind {
    IMAGE,
    VIDEO,
}

/** One Proton photo or video shown by the gallery. */
data class GalleryAsset(
    val stableId: String,
    val capturedAtEpochMillis: Long,
    val nodeUid: String,
    val hasThumbnail: Boolean,
    val displayName: String = "",
    val mediaKind: MediaKind = MediaKind.IMAGE,
    val tags: Set<ProtonMediaTag> = emptySet(),
) {
    val isFavorite: Boolean
        get() = ProtonMediaTag.FAVORITES in tags

    fun withFavorite(favorite: Boolean): GalleryAsset =
        copy(
            tags = if (favorite) tags + ProtonMediaTag.FAVORITES else tags - ProtonMediaTag.FAVORITES,
        )
}
