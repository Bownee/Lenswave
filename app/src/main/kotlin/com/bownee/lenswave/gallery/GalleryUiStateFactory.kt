package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMetadataState
import com.bownee.lenswave.proton.ProtonThumbnailWorkStatus
import com.bownee.lenswave.proton.ProtonTrashState
import me.proton.core.domain.entity.UserId

internal enum class ProtonAccountStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

internal data class GalleryUiInputs(
    val destination: GalleryDestination = GalleryDestination.Device(),
    val hasDeviceAccess: Boolean = false,
    val supportsDeviceTrash: Boolean = true,
    val selectedDeviceCollection: DeviceCollection = DeviceCollection.CAMERA,
    val devicePhotos: GallerySourceSnapshot<GalleryAsset> = GallerySourceSnapshot(),
    val deviceTrash: GallerySourceSnapshot<GalleryAsset> = GallerySourceSnapshot(),
    val protonGallery: ProtonGalleryState = ProtonGalleryState(),
    val protonMetadata: ProtonMetadataState = ProtonMetadataState(),
    val protonAlbums: ProtonAlbumsState = ProtonAlbumsState(),
    val protonAlbumPhotos: ProtonAlbumPhotosState = ProtonAlbumPhotosState(),
    val protonTrash: ProtonTrashState = ProtonTrashState(),
    val combinedMatches: Map<String, List<String>> = emptyMap(),
    val combinedMatchProgress: CombinedMatchProgress = CombinedMatchProgress(complete = true),
    val currentUserId: UserId? = null,
    val protonAccountStatus: ProtonAccountStatus = ProtonAccountStatus.DISCONNECTED,
    val isRefreshing: Boolean = false,
)

internal class GalleryUiStateFactory(private val text: GalleryText) {
    fun create(inputs: GalleryUiInputs): GalleryUiState = when (val destination = inputs.destination) {
        GalleryDestination.Combined -> combined(inputs)
        is GalleryDestination.Device -> device(inputs, destination)
        GalleryDestination.ProtonTimeline -> protonTimeline(inputs)
        GalleryDestination.ProtonAlbums -> protonAlbums(inputs)
        is GalleryDestination.ProtonAlbumPhotos -> protonAlbum(inputs, destination)
        is GalleryDestination.Trash -> trash(inputs, destination)
    }

    private fun combined(inputs: GalleryUiInputs): GalleryUiState {
        if (!inputs.hasDeviceAccess) return base(
            inputs = inputs,
            emptyState = deviceAccessEmptyState(),
        )
        val protonAssets = if (inputs.protonAccountStatus == ProtonAccountStatus.CONNECTED) {
            inputs.protonGallery.photos.map { it.toGalleryAsset() }
        } else {
            emptyList()
        }
        val assets = CombinedGallery.merge(inputs.devicePhotos.items, protonAssets, inputs.combinedMatches)
        val status = buildString {
            append(photoCountStatus(assets.size))
            if (inputs.protonAccountStatus == ProtonAccountStatus.CONNECTED) {
                protonLoadingDetail(inputs)?.let { detail ->
                    append(text.string(R.string.status_separator))
                    append(detail)
                }
            }
            if (inputs.protonAccountStatus == ProtonAccountStatus.DISCONNECTED) {
                append(text.string(R.string.status_separator))
                append(text.string(R.string.proton_not_connected))
            }
            if (inputs.combinedMatchProgress.errorMessage != null) {
                append(text.string(R.string.status_separator))
                append(text.string(R.string.duplicate_check_incomplete))
            }
        }
        val emptyState = when {
            assets.isNotEmpty() || inputs.devicePhotos.isLoading || inputs.isInitialMetadataLoading() -> null
            inputs.protonAccountStatus == ProtonAccountStatus.DISCONNECTED -> GalleryEmptyState(
                title = text.string(R.string.no_device_photos),
                message = text.string(R.string.connect_proton_add_timeline),
                actionLabel = text.string(R.string.connect_proton),
                action = GalleryEmptyAction.CONNECT_PROTON,
            )
            else -> GalleryEmptyState(
                title = text.string(R.string.no_photos),
                message = text.string(R.string.device_proton_photos_appear_here),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), status, emptyState)
    }

    private fun device(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.Device,
    ): GalleryUiState {
        if (!inputs.hasDeviceAccess) return base(
            inputs = inputs,
            emptyState = deviceAccessEmptyState(),
        )
        val assets = if (destination.collection == DeviceCollection.ALL) {
            inputs.devicePhotos.items
        } else {
            inputs.devicePhotos.items.filter { it.deviceCollection == destination.collection }
        }
        val label = if (destination.collection == DeviceCollection.ALL) {
            text.string(R.string.photos)
        } else {
            text.string(destination.collection.labelRes)
        }
        val status = if (inputs.devicePhotos.errorMessage != null) {
            status(label, text.string(R.string.could_not_refresh))
        } else {
            status(label, photoCountStatus(assets.size))
        }
        val emptyState = when {
            assets.isNotEmpty() || inputs.devicePhotos.isLoading || !inputs.devicePhotos.hasLoaded -> null
            inputs.devicePhotos.errorMessage != null -> GalleryEmptyState(
                title = text.string(R.string.could_not_read_device_photos),
                message = text.string(R.string.could_not_refresh_device_photos),
            )
            else -> GalleryEmptyState(
                title = if (destination.collection == DeviceCollection.ALL) {
                    text.string(R.string.no_device_photos)
                } else {
                    text.string(R.string.no_collection_photos, label)
                },
                message = text.string(R.string.photos_from_source_appear_here),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), status, emptyState)
    }

    private fun protonTimeline(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val assets = inputs.protonGallery.photos.map { it.toGalleryAsset() }
        val statusDetail = when {
            inputs.isInitialMetadataLoading() -> text.string(R.string.loading_metadata)
            inputs.protonGallery.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(inputs) ?: photoCountStatus(assets.size)
        }
        val status = status(text.string(R.string.photos), statusDetail)
        val emptyState = when {
            assets.isNotEmpty() -> null
            inputs.protonGallery.errorMessage != null -> GalleryEmptyState(
                text.string(R.string.could_not_load_proton_photos),
                text.string(R.string.check_connection_refresh),
            )
            inputs.isInitialMetadataLoading() || !inputs.protonGallery.hasLoaded -> null
            else -> GalleryEmptyState(
                text.string(R.string.no_proton_photos),
                text.string(R.string.proton_photos_appear_here),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), status, emptyState)
    }

    private fun protonAlbums(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val state = inputs.protonAlbums
        val albums = state.albums
        val statusDetail = when {
            inputs.isInitialMetadataLoading() -> text.string(R.string.loading_metadata)
            state.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(inputs) ?: albumCountStatus(albums.size)
        }
        val status = status(text.string(R.string.albums), statusDetail)
        val emptyState = when {
            albums.isNotEmpty() -> null
            state.errorMessage != null -> GalleryEmptyState(
                text.string(R.string.could_not_load_proton_albums),
                text.string(R.string.check_connection_refresh),
            )
            inputs.isInitialMetadataLoading() || !state.hasLoaded -> null
            else -> GalleryEmptyState(
                text.string(R.string.no_proton_albums),
                text.string(R.string.proton_albums_appear_here),
            )
        }
        return base(inputs, GalleryContent.Albums(albums), status, emptyState)
    }

    private fun protonAlbum(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.ProtonAlbumPhotos,
    ): GalleryUiState {
        protonUnavailable(inputs)?.let { return it }
        val albumState = inputs.protonAlbumPhotos.takeIf { it.albumUid == destination.album.nodeUid }
            ?: ProtonAlbumPhotosState()
        val assets = albumState.photos.map { it.toGalleryAsset() }
        val statusDetail = when {
            albumState.syncing && !albumState.hasLoaded -> text.string(R.string.loading_metadata)
            albumState.errorMessage != null -> text.string(R.string.could_not_refresh)
            albumState.downloadingThumbnails && albumState.downloadedThumbnailCount < albumState.photos.size ->
                text.string(
                    R.string.downloading_thumbnails_progress,
                    albumState.downloadedThumbnailCount,
                    albumState.photos.size,
                )
            else -> photoCountStatus(assets.size)
        }
        val status = status(destination.album.name, statusDetail)
        val emptyState = when {
            assets.isNotEmpty() || albumState.syncing || !albumState.hasLoaded -> null
            albumState.errorMessage != null -> GalleryEmptyState(
                title = text.string(R.string.could_not_load_album),
                message = text.string(R.string.check_connection_refresh),
            )
            else -> GalleryEmptyState(
                title = text.string(R.string.album_empty),
                message = text.string(R.string.album_photos_appear_here, destination.album.name),
            )
        }
        return base(inputs, GalleryContent.Photos(assets), status, emptyState)
    }

    private fun trash(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.Trash,
    ): GalleryUiState = when (destination.source) {
        PhotoSource.DEVICE -> deviceTrash(inputs)
        PhotoSource.PROTON -> protonTrash(inputs)
    }

    private fun deviceTrash(inputs: GalleryUiInputs): GalleryUiState {
        if (!inputs.supportsDeviceTrash) return base(
            inputs = inputs,
            statusText = status(text.string(R.string.trash), text.string(R.string.requires_android_11)),
            emptyState = GalleryEmptyState(
                title = text.string(R.string.device_trash_unavailable),
                message = text.string(R.string.device_trash_unavailable_message),
            ),
        )
        if (!inputs.hasDeviceAccess) return base(
            inputs = inputs,
            emptyState = deviceAccessEmptyState(),
        )
        val assets = inputs.deviceTrash.items
        val status = if (inputs.deviceTrash.errorMessage != null) {
            status(text.string(R.string.trash), text.string(R.string.could_not_refresh))
        } else {
            status(text.string(R.string.trash), photoCountStatus(assets.size))
        }
        val emptyState = if (assets.isEmpty()) {
            GalleryEmptyState(
                title = text.string(R.string.device_trash_empty),
                message = text.string(R.string.device_trash_empty_message),
            )
        } else {
            null
        }
        return base(
            inputs = inputs,
            content = GalleryContent.Photos(assets),
            statusText = status,
            emptyState = emptyState,
            showDeleteAll = assets.isNotEmpty() && !inputs.deviceTrash.isLoading,
        )
    }

    private fun protonTrash(inputs: GalleryUiInputs): GalleryUiState {
        protonUnavailable(inputs, area = R.string.trash)?.let { return it }
        val state = inputs.protonTrash
        val assets = ProtonTrashGallery.createPhotos(state.photos)
        val statusDetail = when {
            inputs.isInitialMetadataLoading() -> text.string(R.string.loading_metadata)
            state.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> protonLoadingDetail(inputs) ?: photoCountStatus(assets.size)
        }
        val status = status(text.string(R.string.trash), statusDetail)
        val emptyState = when {
            assets.isNotEmpty() -> null
            state.errorMessage != null -> GalleryEmptyState(
                text.string(R.string.could_not_load_proton_trash),
                text.string(R.string.check_connection_refresh),
            )
            inputs.isInitialMetadataLoading() || !state.hasLoaded -> null
            else -> GalleryEmptyState(
                text.string(R.string.trash_empty),
                text.string(R.string.proton_trash_empty_message),
            )
        }
        return base(
            inputs = inputs,
            content = GalleryContent.Photos(assets),
            statusText = status,
            emptyState = emptyState,
            showDeleteAll = assets.isNotEmpty() && !state.syncing,
        )
    }

    private fun protonUnavailable(
        inputs: GalleryUiInputs,
        area: Int = R.string.photos,
    ): GalleryUiState? = when (inputs.protonAccountStatus) {
        ProtonAccountStatus.DISCONNECTED -> base(
            inputs = inputs,
            statusText = status(text.string(area), text.string(R.string.proton_not_connected)),
            emptyState = GalleryEmptyState(
                title = text.string(R.string.connect_proton_photos),
                message = text.string(R.string.connect_proton_message),
                actionLabel = text.string(R.string.connect_proton),
                action = GalleryEmptyAction.CONNECT_PROTON,
            ),
        )
        ProtonAccountStatus.CONNECTING -> base(inputs)
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
        content = content,
        statusText = statusText,
        emptyState = emptyState,
        currentUserId = inputs.currentUserId,
        isProtonConnected = inputs.currentUserId != null,
        isRefreshing = inputs.isRefreshing,
        showDeleteAll = showDeleteAll,
        selectedDeviceCollection = inputs.selectedDeviceCollection,
    )

    private fun GalleryUiInputs.isInitialMetadataLoading(): Boolean =
        protonMetadata.isLoading && !protonMetadata.hasLoaded

    private fun protonLoadingDetail(inputs: GalleryUiInputs): String? {
        if (inputs.isInitialMetadataLoading()) return text.string(R.string.loading_metadata)
        if (inputs.protonGallery.thumbnailWorkStatus !is ProtonThumbnailWorkStatus.Running) return null
        val progress = ProtonThumbnailProgressCalculator.calculate(
            timeline = inputs.protonGallery.photos,
            albums = inputs.protonAlbums.albums,
            trash = inputs.protonTrash.photos,
        )
        if (progress.downloaded >= progress.total) return null
        return text.string(
            R.string.downloading_thumbnails_progress,
            progress.downloaded,
            progress.total,
        )
    }

    private fun deviceAccessEmptyState() = GalleryEmptyState(
        title = text.string(R.string.allow_photo_access),
        message = text.string(R.string.allow_photo_access_message),
        actionLabel = text.string(R.string.allow_access),
        action = GalleryEmptyAction.REQUEST_DEVICE_ACCESS,
    )

    private fun photoCountStatus(count: Int): String =
        text.quantity(R.plurals.photo_count, count, count)

    private fun albumCountStatus(count: Int): String =
        text.quantity(R.plurals.album_count, count, count)

    private fun status(label: String, detail: String): String =
        text.string(R.string.status_with_detail, label, detail)
}
