package com.bownee.lenswave.proton

data class ProtonAlbum(
    val nodeUid: String,
    val name: String,
    val photoCount: Long,
    val coverPhotoNodeUid: String?,
    val createdAtEpochSeconds: Long,
    val lastActivityEpochSeconds: Long,
    val hasCoverThumbnail: Boolean,
    val isShared: Boolean,
) {
    fun reference() = ProtonAlbumReference(nodeUid = nodeUid, name = name)
}

/** Stable navigation identity; mutable album metadata stays in repository state. */
data class ProtonAlbumReference(
    val nodeUid: String,
    val name: String,
)

data class ProtonAlbumsState(
    val userId: String? = null,
    val albums: List<ProtonAlbum> = emptyList(),
    val hasLoaded: Boolean = false,
    val syncing: Boolean = false,
    val downloadedCoverCount: Int = 0,
    val errorMessage: String? = null,
)

data class ProtonAlbumPhotosState(
    val userId: String? = null,
    val albumUid: String? = null,
    val albumName: String = "",
    val photos: List<ProtonGalleryPhoto> = emptyList(),
    val syncing: Boolean = false,
    val downloadedThumbnailCount: Int = 0,
    val errorMessage: String? = null,
)
