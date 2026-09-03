package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTagState
import me.proton.core.domain.entity.UserId

internal enum class ProtonAccountStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ;

    companion object {
        fun resolve(
            initialized: Boolean,
            transitioning: Boolean,
            hasAccount: Boolean,
            accountIsReady: Boolean,
        ): ProtonAccountStatus =
            when {
                !initialized || transitioning -> CONNECTING
                !hasAccount -> DISCONNECTED
                accountIsReady -> CONNECTED
                else -> CONNECTING
            }
    }
}

internal data class GalleryUiInputs(
    val destination: GalleryDestination = GalleryDestination.Timeline,
    val protonGallery: ProtonGalleryState = ProtonGalleryState(),
    val protonAlbums: ProtonAlbumsState = ProtonAlbumsState(),
    val protonAlbumPhotos: ProtonAlbumPhotosState = ProtonAlbumPhotosState(),
    val currentUserId: UserId? = null,
    val protonAccountStatus: ProtonAccountStatus = ProtonAccountStatus.DISCONNECTED,
    val isRefreshing: Boolean = false,
)

internal class GalleryUiStateFactory(
    private val text: GalleryText,
) {
    fun create(inputs: GalleryUiInputs): GalleryUiState =
        when (val destination = inputs.destination) {
            GalleryDestination.Timeline -> timeline(inputs)
            is GalleryDestination.Tag -> tag(inputs, destination)
            GalleryDestination.Library -> library(inputs)
            is GalleryDestination.AlbumPhotos -> album(inputs, destination)
        }

    private fun timeline(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val tagIndex = inputs.protonGallery.tagIndex()
        val assets = inputs.protonGallery.photos.map { it.toGalleryAsset(tagIndex) }
        val emptyState =
            when {
                assets.isNotEmpty() -> {
                    null
                }

                inputs.protonGallery.refreshFailed -> {
                    GalleryEmptyState(
                        text.string(R.string.could_not_load_proton_photos),
                        text.string(R.string.check_connection_refresh),
                    )
                }

                !inputs.protonGallery.hasLoaded -> {
                    null
                }

                else -> {
                    GalleryEmptyState(
                        text.string(R.string.no_proton_photos),
                        text.string(R.string.proton_photos_appear_here),
                    )
                }
            }
        return base(inputs, GalleryContent.Photos(assets), emptyState)
    }

    private fun tag(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.Tag,
    ): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val state = inputs.protonGallery.tags[destination.tag] ?: ProtonTagState()
        val tagIndex = inputs.protonGallery.tagIndex()
        val assets = state.photos.map { it.toGalleryAsset(tagIndex) }
        val label = text.string(destination.tag.labelRes)
        val emptyState =
            when {
                assets.isNotEmpty() -> {
                    null
                }

                state.refreshFailed -> {
                    GalleryEmptyState(
                        text.string(R.string.could_not_load_proton_tag, label),
                        text.string(R.string.check_connection_refresh),
                    )
                }

                !state.hasLoaded -> {
                    null
                }

                else -> {
                    GalleryEmptyState(
                        text.string(R.string.no_media_with_tag, label),
                        text.string(R.string.proton_tagged_media_appear_here),
                    )
                }
            }
        return base(inputs, GalleryContent.Photos(assets), emptyState)
    }

    private fun library(inputs: GalleryUiInputs): GalleryUiState {
        val albums = inputs.protonAlbums
        val sections =
            buildList {
                when (inputs.protonAccountStatus) {
                    ProtonAccountStatus.DISCONNECTED -> {
                        add(
                            LibrarySection(
                                key = "proton",
                                title = "",
                                items =
                                    listOf(
                                        entry(
                                            key = "connect-proton",
                                            label = text.string(R.string.connect_proton),
                                            iconRes = R.drawable.ic_cloud,
                                            action = LibraryAction.Request(GalleryEmptyAction.CONNECT_PROTON),
                                        ),
                                    ),
                            ),
                        )
                    }

                    ProtonAccountStatus.CONNECTING,
                    ProtonAccountStatus.CONNECTED,
                    -> {
                        // The tab itself is titled Albums, so the grid needs no heading of its own.
                        if (albums.albums.isNotEmpty()) {
                            add(
                                LibrarySection(
                                    key = "albums",
                                    title = "",
                                    items = albums.albums.map(LibraryItem::Album),
                                ),
                            )
                        }
                    }
                }
            }
        // Mirrors the media-type filters: an empty panel once the list has loaded and is empty.
        val emptyState =
            when {
                inputs.protonAccountStatus != ProtonAccountStatus.CONNECTED -> {
                    null
                }

                albums.albums.isNotEmpty() -> {
                    null
                }

                albums.refreshFailed -> {
                    GalleryEmptyState(
                        text.string(R.string.could_not_load_albums),
                        text.string(R.string.check_connection_refresh),
                    )
                }

                !albums.hasLoaded -> {
                    null
                }

                else -> {
                    GalleryEmptyState(
                        text.string(R.string.no_albums),
                        text.string(R.string.proton_albums_appear_here),
                    )
                }
            }
        return base(
            inputs = inputs,
            content = GalleryContent.Library(sections),
            emptyState = emptyState,
        )
    }

    private fun entry(
        key: String,
        label: String,
        iconRes: Int,
        action: LibraryAction,
    ) = LibraryItem.Entry(key = key, label = label, iconRes = iconRes, action = action)

    private fun album(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.AlbumPhotos,
    ): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val albumState =
            inputs.protonAlbumPhotos.takeIf { it.albumUid == destination.album.nodeUid }
                ?: ProtonAlbumPhotosState()
        val tagIndex = inputs.protonGallery.tagIndex()
        val assets = albumState.photos.map { it.toGalleryAsset(tagIndex) }
        val emptyState =
            when {
                assets.isNotEmpty() || !albumState.hasLoaded -> {
                    null
                }

                albumState.refreshFailed -> {
                    GalleryEmptyState(
                        title = text.string(R.string.could_not_load_album),
                        message = text.string(R.string.check_connection_refresh),
                    )
                }

                else -> {
                    GalleryEmptyState(
                        title = text.string(R.string.album_empty),
                        message = text.string(R.string.album_photos_appear_here, destination.album.name),
                    )
                }
            }
        return base(inputs, GalleryContent.Photos(assets), emptyState)
    }

    private fun protonUnavailable(inputs: GalleryUiInputs): GalleryUiState? =
        when (inputs.protonAccountStatus) {
            ProtonAccountStatus.DISCONNECTED -> {
                base(
                    inputs = inputs,
                    emptyState =
                        GalleryEmptyState(
                            title = text.string(R.string.connect_proton_photos),
                            message = text.string(R.string.connect_proton_message),
                            actionLabel = text.string(R.string.connect_proton),
                            action = GalleryEmptyAction.CONNECT_PROTON,
                        ),
                )
            }

            ProtonAccountStatus.CONNECTING -> {
                base(
                    inputs = inputs,
                    emptyState =
                        GalleryEmptyState(
                            title = text.string(R.string.loading_metadata),
                            message = "",
                        ),
                )
            }

            ProtonAccountStatus.CONNECTED -> {
                null
            }
        }

    private fun base(
        inputs: GalleryUiInputs,
        content: GalleryContent = GalleryContent.Photos(emptyList()),
        emptyState: GalleryEmptyState? = null,
    ) = GalleryUiState(
        destination = inputs.destination,
        title = title(inputs.destination),
        content = content.sorted(),
        emptyState = emptyState,
        currentUserId = inputs.currentUserId,
        isProtonConnected = inputs.currentUserId != null,
        isRefreshing = inputs.isRefreshing,
    )

    /** Photo pages are published newest first so every consumer sees the same order as the grid. */
    private fun GalleryContent.sorted(): GalleryContent =
        when (this) {
            is GalleryContent.Photos -> GalleryContent.Photos(GalleryGrouping.sortPhotos(assets))
            is GalleryContent.Library -> this
        }

    private fun title(destination: GalleryDestination): String =
        when (destination) {
            GalleryDestination.Timeline -> text.string(R.string.photos)
            GalleryDestination.Library -> text.string(R.string.albums)
            is GalleryDestination.Tag -> text.string(destination.tag.labelRes)
            is GalleryDestination.AlbumPhotos -> destination.album.name
        }

    private fun ProtonGalleryPhoto.toGalleryAsset(tagIndex: Map<String, Set<ProtonMediaTag>>): GalleryAsset {
        val tags = tagIndex[nodeUid].orEmpty()
        return toGalleryAsset(
            mediaKind = if (ProtonMediaTag.VIDEOS in tags) MediaKind.VIDEO else MediaKind.IMAGE,
            tags = tags,
        )
    }

    private fun ProtonGalleryState.tagIndex(): Map<String, Set<ProtonMediaTag>> =
        buildMap {
            tags.forEach { (tag, state) ->
                state.photos.forEach { photo -> put(photo.nodeUid, get(photo.nodeUid).orEmpty() + tag) }
            }
        }
}
