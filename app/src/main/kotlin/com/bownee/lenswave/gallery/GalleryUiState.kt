package com.bownee.lenswave.gallery

import androidx.annotation.DrawableRes
import com.bownee.lenswave.proton.ProtonAlbum
import me.proton.core.domain.entity.UserId

sealed interface GalleryContent {
    data class Photos(
        val assets: List<GalleryAsset>,
    ) : GalleryContent

    data class Library(
        val sections: List<LibrarySection>,
    ) : GalleryContent
}

data class LibrarySection(
    val key: String,
    val title: String,
    val items: List<LibraryItem>,
)

sealed interface LibraryItem {
    data class Album(
        val album: ProtonAlbum,
    ) : LibraryItem

    data class Entry(
        val key: String,
        val label: String,
        @param:DrawableRes val iconRes: Int,
        val action: LibraryAction,
    ) : LibraryItem
}

sealed interface LibraryAction {
    data class Open(
        val destination: GalleryDestination,
    ) : LibraryAction

    data class Request(
        val action: GalleryEmptyAction,
    ) : LibraryAction
}

enum class GalleryEmptyAction {
    CONNECT_PROTON,
}

data class GalleryEmptyState(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val action: GalleryEmptyAction? = null,
)

data class GalleryUiState(
    val destination: GalleryDestination = GalleryDestination.Timeline,
    val title: String = "",
    val content: GalleryContent = GalleryContent.Photos(emptyList()),
    val emptyState: GalleryEmptyState? = null,
    val currentUserId: UserId? = null,
    val isProtonConnected: Boolean = false,
    val isRefreshing: Boolean = false,
) {
    val visibleAssets: List<GalleryAsset>
        get() = (content as? GalleryContent.Photos)?.assets.orEmpty()
}
