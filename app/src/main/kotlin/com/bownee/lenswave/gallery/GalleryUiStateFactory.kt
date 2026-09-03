package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTagState
import com.bownee.lenswave.proton.ProtonTrashState
import me.proton.core.domain.entity.UserId

internal enum class ProtonAccountStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED;

    companion object {
        fun resolve(
            initialized: Boolean,
            transitioning: Boolean,
            hasAccount: Boolean,
            accountIsReady: Boolean,
        ): ProtonAccountStatus = when {
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
    val protonTrash: ProtonTrashState = ProtonTrashState(),
    val currentUserId: UserId? = null,
    val protonAccountStatus: ProtonAccountStatus = ProtonAccountStatus.DISCONNECTED,
    val isRefreshing: Boolean = false,
)

internal class GalleryUiStateFactory(private val text: GalleryText) {
    fun create(inputs: GalleryUiInputs): GalleryUiState = when (val destination = inputs.destination) {
        GalleryDestination.Timeline -> timeline(inputs)
        is GalleryDestination.Tag -> tag(inputs, destination)
        GalleryDestination.Library -> library(inputs)
        is GalleryDestination.AlbumPhotos -> album(inputs, destination)
        GalleryDestination.Trash -> trash(inputs)
    }

    private fun timeline(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val tagIndex = inputs.protonGallery.tagIndex()
        val assets = inputs.protonGallery.photos.map { it.toGalleryAsset(tagIndex) }
        val statusDetail = when {
            inputs.shouldShowMetadataLoading(
                inputs.protonGallery.syncing,
                inputs.protonGallery.hasLoaded,
            ) -> text.string(R.string.loading_metadata)
            inputs.protonGallery.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(
                ProtonThumbnailProgressCalculator.timeline(inputs.protonGallery.photos),
            ) ?: photoCountStatus(assets.size)
        }
        val emptyState = when {
            assets.isNotEmpty() -> null
            inputs.protonGallery.errorMessage != null -> GalleryEmptyState(
                text.string(R.string.could_not_load_proton_photos),
                text.string(R.string.check_connection_refresh),
            )
            !inputs.protonGallery.hasLoaded -> null
            else -> GalleryEmptyState(
                text.string(R.string.no_proton_photos),
                text.string(R.string.proton_photos_appear_here),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), statusDetail, emptyState)
    }

    private fun tag(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.Tag,
    ): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val state = inputs.protonGallery.tags[destination.tag] ?: ProtonTagState()
        val tagIndex = inputs.protonGallery.tagIndex()
        val assets = state.photos.map { it.toGalleryAsset(tagIndex) }
        val statusDetail = when {
            state.syncing || !state.hasLoaded -> text.string(R.string.loading_metadata)
            state.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(
                ProtonThumbnailProgressCalculator.timeline(state.photos),
            ) ?: photoCountStatus(assets.size)
        }
        val label = text.string(destination.tag.labelRes)
        val emptyState = when {
            assets.isNotEmpty() -> null
            state.errorMessage != null -> GalleryEmptyState(
                text.string(R.string.could_not_load_proton_filter, label),
                text.string(R.string.check_connection_refresh),
            )
            !state.hasLoaded -> null
            else -> GalleryEmptyState(
                text.string(R.string.no_media_in_filter, label),
                text.string(R.string.proton_filtered_media_appear_here),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), statusDetail, emptyState)
    }

    private fun library(inputs: GalleryUiInputs): GalleryUiState {
        val albums = inputs.protonAlbums
        val sections = buildList {
            when (inputs.protonAccountStatus) {
                ProtonAccountStatus.DISCONNECTED -> add(
                    LibrarySection(
                        key = "proton",
                        title = "",
                        items = listOf(
                            entry(
                                key = "connect-proton",
                                label = text.string(R.string.connect_proton),
                                iconRes = R.drawable.ic_cloud,
                                action = LibraryAction.Request(GalleryEmptyAction.CONNECT_PROTON),
                            ),
                        ),
                    ),
                )

                ProtonAccountStatus.CONNECTING,
                ProtonAccountStatus.CONNECTED,
                -> {
                    if (albums.albums.isNotEmpty()) add(
                        LibrarySection(
                            key = "albums",
                            title = text.string(R.string.albums),
                            items = albums.albums.map(LibraryItem::Album),
                        ),
                    )
                    add(
                        LibrarySection(
                            key = "media-types",
                            title = text.string(R.string.media_types),
                            items = ProtonMediaTag.entries.map { tag ->
                                entry(
                                    key = "tag:${tag.name}",
                                    label = text.string(tag.labelRes),
                                    iconRes = when (tag) {
                                        ProtonMediaTag.FAVORITES -> R.drawable.ic_favorite
                                        ProtonMediaTag.SCREENSHOTS -> R.drawable.ic_screenshot
                                        ProtonMediaTag.VIDEOS -> R.drawable.ic_play
                                        ProtonMediaTag.LIVE_PHOTOS -> R.drawable.ic_live
                                        ProtonMediaTag.MOTION_PHOTOS -> R.drawable.ic_motion
                                        ProtonMediaTag.SELFIES -> R.drawable.ic_person
                                        ProtonMediaTag.PORTRAITS -> R.drawable.ic_portrait
                                        ProtonMediaTag.BURSTS -> R.drawable.ic_burst
                                        ProtonMediaTag.PANORAMAS -> R.drawable.ic_panorama
                                        ProtonMediaTag.RAW -> R.drawable.ic_camera
                                    },
                                    action = LibraryAction.Open(GalleryDestination.Tag(tag)),
                                )
                            },
                        ),
                    )
                    add(
                        LibrarySection(
                            key = "trash",
                            title = "",
                            items = listOf(
                                entry(
                                    key = "trash",
                                    label = text.string(R.string.trash),
                                    iconRes = R.drawable.ic_delete,
                                    action = LibraryAction.Open(GalleryDestination.Trash),
                                ),
                            ),
                        ),
                    )
                }
            }
        }
        val statusDetail = when (inputs.protonAccountStatus) {
            ProtonAccountStatus.DISCONNECTED -> text.string(R.string.proton_not_connected)
            ProtonAccountStatus.CONNECTING -> text.string(R.string.loading_metadata)
            ProtonAccountStatus.CONNECTED -> when {
                inputs.shouldShowMetadataLoading(albums.syncing, albums.hasLoaded) ->
                    text.string(R.string.loading_metadata)
                albums.errorMessage != null -> text.string(R.string.could_not_refresh)
                else -> protonLoadingDetail(
                    ProtonThumbnailProgressCalculator.albumCovers(albums.albums),
                ) ?: albumCountStatus(albums.albums.size)
            }
        }
        return base(
            inputs = inputs,
            content = GalleryContent.Library(sections),
            statusText = statusDetail,
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
        val albumState = inputs.protonAlbumPhotos.takeIf { it.albumUid == destination.album.nodeUid }
            ?: ProtonAlbumPhotosState()
        val tagIndex = inputs.protonGallery.tagIndex()
        val assets = albumState.photos.map { it.toGalleryAsset(tagIndex) }
        val statusDetail = when {
            inputs.shouldShowMetadataLoading(albumState.syncing, albumState.hasLoaded) ->
                text.string(R.string.loading_metadata)
            albumState.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(
                ProtonThumbnailProgressCalculator.timeline(albumState.photos),
            ) ?: photoCountStatus(assets.size)
        }
        val emptyState = when {
            assets.isNotEmpty() || !albumState.hasLoaded -> null
            albumState.errorMessage != null -> GalleryEmptyState(
                title = text.string(R.string.could_not_load_album),
                message = text.string(R.string.check_connection_refresh),
            )
            else -> GalleryEmptyState(
                title = text.string(R.string.album_empty),
                message = text.string(R.string.album_photos_appear_here, destination.album.name),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), statusDetail, emptyState)
    }

    private fun trash(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val state = inputs.protonTrash
        val videoNodeUids = inputs.protonGallery.tagNodeUids(ProtonMediaTag.VIDEOS)
        val favoriteNodeUids = inputs.protonGallery.tagNodeUids(ProtonMediaTag.FAVORITES)
        val assets = ProtonTrashGallery.createPhotos(state.photos, videoNodeUids, favoriteNodeUids)
        val statusDetail = when {
            inputs.shouldShowMetadataLoading(state.syncing, state.hasLoaded) ->
                text.string(R.string.loading_metadata)
            state.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(
                ProtonThumbnailProgressCalculator.trash(state.photos),
            ) ?: photoCountStatus(assets.size)
        }
        val emptyState = when {
            assets.isNotEmpty() -> null
            state.errorMessage != null -> GalleryEmptyState(
                text.string(R.string.could_not_load_proton_trash),
                text.string(R.string.check_connection_refresh),
            )
            !state.hasLoaded -> null
            else -> GalleryEmptyState(
                text.string(R.string.trash_empty),
                text.string(R.string.proton_trash_empty_message),
            )
        }
        return base(
            inputs = inputs,
            content = GalleryContent.Photos(assets),
            statusText = statusDetail,
            emptyState = emptyState,
            showDeleteAll = assets.isNotEmpty() && !state.syncing,
        )
    }

    private fun protonUnavailable(inputs: GalleryUiInputs): GalleryUiState? = when (inputs.protonAccountStatus) {
        ProtonAccountStatus.DISCONNECTED -> base(
            inputs = inputs,
            statusText = text.string(R.string.proton_not_connected),
            emptyState = GalleryEmptyState(
                title = text.string(R.string.connect_proton_photos),
                message = text.string(R.string.connect_proton_message),
                actionLabel = text.string(R.string.connect_proton),
                action = GalleryEmptyAction.CONNECT_PROTON,
            ),
        )
        ProtonAccountStatus.CONNECTING -> base(
            inputs = inputs,
            statusText = text.string(R.string.loading_metadata),
            emptyState = GalleryEmptyState(
                title = text.string(R.string.loading_metadata),
                message = "",
            ),
        )
        ProtonAccountStatus.CONNECTED -> null
    }

    private fun base(
        inputs: GalleryUiInputs,
        content: GalleryContent = GalleryContent.Photos(emptyList()),
        statusText: String = "",
        emptyState: GalleryEmptyState? = null,
        showDeleteAll: Boolean = false,
    ) = GalleryUiState(
        destination = inputs.destination,
        title = title(inputs.destination),
        content = content,
        statusText = statusText,
        emptyState = emptyState,
        currentUserId = inputs.currentUserId,
        isProtonConnected = inputs.currentUserId != null,
        isRefreshing = inputs.isRefreshing,
        showDeleteAll = showDeleteAll,
    )

    private fun title(destination: GalleryDestination): String = when (destination) {
        GalleryDestination.Timeline -> text.string(R.string.photos)
        GalleryDestination.Library -> text.string(R.string.library)
        is GalleryDestination.Tag -> text.string(destination.tag.labelRes)
        is GalleryDestination.AlbumPhotos -> destination.album.name
        GalleryDestination.Trash -> text.string(R.string.trash)
    }

    private fun GalleryUiInputs.shouldShowMetadataLoading(
        syncing: Boolean,
        hasLoaded: Boolean,
    ): Boolean = syncing && (!hasLoaded || isRefreshing)

    private fun protonLoadingDetail(progress: ProtonThumbnailProgress): String? {
        if (progress.downloaded >= progress.total) return null
        return text.string(
            R.string.downloading_thumbnails_progress,
            progress.downloaded,
            progress.total,
        )
    }

    private fun photoCountStatus(count: Int): String =
        text.quantity(R.plurals.photo_count, count, count)

    private fun albumCountStatus(count: Int): String =
        text.quantity(R.plurals.album_count, count, count)

    private fun ProtonGalleryPhoto.toGalleryAsset(tagIndex: Map<String, Set<ProtonMediaTag>>): GalleryAsset {
        val tags = tagIndex[nodeUid].orEmpty()
        return toGalleryAsset(
            mediaKind = if (ProtonMediaTag.VIDEOS in tags) MediaKind.VIDEO else MediaKind.IMAGE,
            tags = tags,
        )
    }

    private fun ProtonGalleryState.tagNodeUids(tag: ProtonMediaTag): Set<String> =
        tags[tag]?.photos.orEmpty().mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)

    private fun ProtonGalleryState.tagIndex(): Map<String, Set<ProtonMediaTag>> = buildMap {
        tags.forEach { (tag, state) ->
            state.photos.forEach { photo -> put(photo.nodeUid, get(photo.nodeUid).orEmpty() + tag) }
        }
    }
}
