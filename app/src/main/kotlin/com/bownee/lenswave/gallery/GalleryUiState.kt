package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import me.proton.core.domain.entity.UserId

sealed interface GalleryContent {
    data class Photos(val assets: List<GalleryAsset>) : GalleryContent
    data class Albums(val albums: List<ProtonAlbum>) : GalleryContent
}

enum class GalleryEmptyAction {
    CONNECT_PROTON,
    REQUEST_DEVICE_ACCESS,
}

data class GalleryEmptyState(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val action: GalleryEmptyAction? = null,
)

data class GalleryUiState(
    val destination: GalleryDestination = GalleryDestination.Device(),
    val content: GalleryContent = GalleryContent.Photos(emptyList()),
    val statusText: String = "",
    val emptyState: GalleryEmptyState? = null,
    val currentUserId: UserId? = null,
    val isProtonConnected: Boolean = false,
    val isRefreshing: Boolean = false,
    val showDeleteAll: Boolean = false,
    val selectedDeviceCollection: DeviceCollection = DeviceCollection.CAMERA,
) {
    val visibleAssets: List<GalleryAsset>
        get() = (content as? GalleryContent.Photos)?.assets.orEmpty()

    val isTrash: Boolean
        get() = destination is GalleryDestination.Trash
}

internal data class GallerySourceSnapshot<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)
