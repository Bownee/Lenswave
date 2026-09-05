package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryState
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
    /** The account went away on its own (an expired session), not through the user's disconnect. */
    val signedOut: Boolean = false,
)

internal class GalleryUiStateFactory(
    private val text: GalleryText,
) {
    private val memo = GalleryAssetMemo()

    fun create(inputs: GalleryUiInputs): GalleryUiState =
        when (val destination = inputs.destination) {
            GalleryDestination.Timeline -> timeline(inputs)
            is GalleryDestination.Tag -> tag(inputs, destination)
            GalleryDestination.Library -> library(inputs)
            is GalleryDestination.AlbumPhotos -> album(inputs, destination)
        }

    /**
     * A placeholder for [inputs] that costs O(1): the destination, title and account facts are
     * final, the page is empty and no empty-state panel shows, exactly like a section that has
     * not loaded yet. [create] builds the full state off the main thread and replaces it.
     */
    fun skeleton(inputs: GalleryUiInputs): GalleryUiState =
        base(
            inputs = inputs,
            content = if (inputs.destination == GalleryDestination.Library) NO_SECTIONS else NO_PHOTOS,
        )

    private fun timeline(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val content = memo.photos(inputs.protonGallery.photos, memo.tagIndex(inputs.protonGallery.tags))
        val emptyState =
            when {
                content.assets.isNotEmpty() -> {
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
        return base(inputs, content, emptyState, inputs.protonGallery.listingRefused)
    }

    private fun tag(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.Tag,
    ): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val state = inputs.protonGallery.tags[destination.tag] ?: ProtonTagState()
        val content = memo.photos(state.photos, memo.tagIndex(inputs.protonGallery.tags))
        val label = text.string(destination.tag.labelRes)
        val emptyState =
            when {
                content.assets.isNotEmpty() -> {
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
        return base(inputs, content, emptyState, state.listingRefused)
    }

    private fun library(inputs: GalleryUiInputs): GalleryUiState {
        val albums = inputs.protonAlbums
        val content =
            memo.library(albums.albums, inputs.protonAccountStatus) {
                GalleryContent.Library(librarySections(albums.albums, inputs.protonAccountStatus))
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
            content = content,
            emptyState = emptyState,
            listingRefused = albums.listingRefused,
        )
    }

    private fun librarySections(
        albums: List<ProtonAlbum>,
        accountStatus: ProtonAccountStatus,
    ): List<LibrarySection> =
        buildList {
            when (accountStatus) {
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
                    if (albums.isNotEmpty()) {
                        add(
                            LibrarySection(
                                key = "albums",
                                title = "",
                                items = albums.map(LibraryItem::Album),
                            ),
                        )
                    }
                }
            }
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
        val content = memo.photos(albumState.photos, memo.tagIndex(inputs.protonGallery.tags))
        val emptyState =
            when {
                content.assets.isNotEmpty() || !albumState.hasLoaded -> {
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
        return base(inputs, content, emptyState, albumState.listingRefused)
    }

    private fun protonUnavailable(inputs: GalleryUiInputs): GalleryUiState? =
        when (inputs.protonAccountStatus) {
            ProtonAccountStatus.DISCONNECTED -> {
                base(
                    inputs = inputs,
                    emptyState =
                        if (inputs.signedOut) {
                            GalleryEmptyState(
                                title = text.string(R.string.signed_out),
                                message = text.string(R.string.signed_out_message),
                                actionLabel = text.string(R.string.connect_proton),
                                action = GalleryEmptyAction.CONNECT_PROTON,
                            )
                        } else {
                            GalleryEmptyState(
                                title = text.string(R.string.connect_proton_photos),
                                message = text.string(R.string.connect_proton_message),
                                actionLabel = text.string(R.string.connect_proton),
                                action = GalleryEmptyAction.CONNECT_PROTON,
                            )
                        },
                )
            }

            ProtonAccountStatus.CONNECTING -> {
                // Cached metadata goes on screen at once; only a first launch waits for the sync.
                if (inputs.hasCachedTimeline()) {
                    null
                } else {
                    base(
                        inputs = inputs,
                        emptyState =
                            GalleryEmptyState(
                                title = text.string(R.string.loading_metadata),
                                message = "",
                            ),
                    )
                }
            }

            ProtonAccountStatus.CONNECTED -> {
                null
            }
        }

    private fun GalleryUiInputs.hasCachedTimeline(): Boolean =
        protonGallery.hasLoaded &&
            protonGallery.userId != null &&
            (currentUserId == null || protonGallery.userId == currentUserId.id)

    /** Photo pages come from [memo] newest first, so every consumer sees the same order as the grid. */
    private fun base(
        inputs: GalleryUiInputs,
        content: GalleryContent = NO_PHOTOS,
        emptyState: GalleryEmptyState? = null,
        listingRefused: Boolean = false,
    ) = GalleryUiState(
        destination = inputs.destination,
        title = title(inputs.destination),
        content = content,
        emptyState = emptyState,
        currentUserId = inputs.currentUserId,
        isProtonConnected = inputs.currentUserId != null,
        isRefreshing = inputs.isRefreshing,
        listingRefused = listingRefused,
    )

    private fun title(destination: GalleryDestination): String =
        when (destination) {
            GalleryDestination.Timeline -> text.string(R.string.photos)
            GalleryDestination.Library -> text.string(R.string.albums)
            is GalleryDestination.Tag -> text.string(destination.tag.labelRes)
            is GalleryDestination.AlbumPhotos -> destination.album.name
        }

    private companion object {
        val NO_PHOTOS = GalleryContent.Photos(emptyList())
        val NO_SECTIONS = GalleryContent.Library(emptyList())
    }
}
