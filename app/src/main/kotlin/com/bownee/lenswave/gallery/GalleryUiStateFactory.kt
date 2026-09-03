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
    val destination: GalleryDestination = GalleryDestination.Device(),
    val hasDeviceAccess: Boolean = false,
    val supportsDeviceTrash: Boolean = true,
    val devicePhotos: GallerySourceSnapshot<GalleryAsset> = GallerySourceSnapshot(),
    val deviceTrash: GallerySourceSnapshot<GalleryAsset> = GallerySourceSnapshot(),
    val protonGallery: ProtonGalleryState = ProtonGalleryState(),
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
        is GalleryDestination.ProtonTag -> protonTag(inputs, destination)
        GalleryDestination.Library -> library(inputs)
        is GalleryDestination.ProtonAlbumPhotos -> protonAlbum(inputs, destination)
        is GalleryDestination.Trash -> trash(inputs, destination)
    }

    private fun combined(inputs: GalleryUiInputs): GalleryUiState {
        val deviceAssets = if (inputs.hasDeviceAccess) inputs.devicePhotos.items else emptyList()
        val protonAssets = if (inputs.protonAccountStatus == ProtonAccountStatus.CONNECTED) {
            val tagIndex = inputs.protonGallery.tagIndex()
            inputs.protonGallery.photos.map { it.toGalleryAsset(tagIndex) }
        } else {
            emptyList()
        }
        val assets = CombinedGallery.merge(deviceAssets, protonAssets, inputs.combinedMatches)
        val status = buildString {
            append(photoCountStatus(assets.size))
            if (inputs.hasDeviceAccess && inputs.devicePhotos.isLoading) {
                append(text.string(R.string.status_separator))
                append(text.string(R.string.loading_metadata))
            } else if (inputs.protonAccountStatus == ProtonAccountStatus.CONNECTING) {
                append(text.string(R.string.status_separator))
                append(text.string(R.string.loading_metadata))
            } else if (inputs.protonAccountStatus == ProtonAccountStatus.CONNECTED) {
                val detail = if (inputs.shouldShowMetadataLoading(
                        inputs.protonGallery.syncing,
                        inputs.protonGallery.hasLoaded,
                    )
                ) {
                    text.string(R.string.loading_metadata)
                } else {
                    protonLoadingDetail(
                        ProtonThumbnailProgressCalculator.timeline(inputs.protonGallery.photos),
                    )
                }
                detail?.let {
                    append(text.string(R.string.status_separator))
                    append(it)
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
            assets.isNotEmpty() || (inputs.hasDeviceAccess && inputs.devicePhotos.isLoading) -> null
            inputs.protonAccountStatus == ProtonAccountStatus.CONNECTED &&
                !inputs.protonGallery.hasLoaded -> null
            inputs.protonAccountStatus == ProtonAccountStatus.CONNECTING -> loadingMetadataEmptyState()
            !inputs.hasDeviceAccess && inputs.protonAccountStatus == ProtonAccountStatus.DISCONNECTED ->
                deviceAccessEmptyState()
            !inputs.hasDeviceAccess -> GalleryEmptyState(
                title = text.string(R.string.no_photos),
                message = text.string(R.string.allow_photo_access_message),
                actionLabel = text.string(R.string.allow_photo_access),
                action = GalleryEmptyAction.REQUEST_DEVICE_ACCESS,
            )
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
        val statusDetail = when {
            inputs.devicePhotos.isLoading -> text.string(R.string.loading_metadata)
            inputs.devicePhotos.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> photoCountStatus(assets.size)
        }
        val status = status(label, statusDetail)
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
        val status = status(text.string(R.string.photos), statusDetail)
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
        return base(inputs, GalleryContent.Photos(assets), status, emptyState)
    }

    private fun protonTag(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.ProtonTag,
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
        return base(
            inputs,
            GalleryContent.Photos(assets),
            status(label, statusDetail),
            emptyState,
        )
    }

    private fun library(inputs: GalleryUiInputs): GalleryUiState {
        val protonAvailable = inputs.protonAccountStatus != ProtonAccountStatus.DISCONNECTED
        val albums = inputs.protonAlbums
        val sections = buildList {
            when (inputs.protonAccountStatus) {
                ProtonAccountStatus.DISCONNECTED -> add(
                    LibrarySection(
                        key = "proton",
                        title = text.string(R.string.space_proton),
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
                                        ProtonMediaTag.VIDEOS -> R.drawable.ic_play
                                        else -> R.drawable.ic_label
                                    },
                                    action = LibraryAction.Open(GalleryDestination.ProtonTag(tag)),
                                )
                            },
                        ),
                    )
                }
            }
            add(
                LibrarySection(
                    key = "device",
                    title = text.string(R.string.space_device),
                    items = if (inputs.hasDeviceAccess) {
                        DeviceCollection.entries.filter { it != DeviceCollection.ALL }.map { collection ->
                            entry(
                                key = "device:${collection.name}",
                                label = text.string(collection.labelRes),
                                iconRes = R.drawable.ic_folder,
                                action = LibraryAction.Open(GalleryDestination.Device(collection)),
                            )
                        }
                    } else {
                        listOf(
                            entry(
                                key = "allow-device-access",
                                label = text.string(R.string.allow_photo_access),
                                iconRes = R.drawable.ic_photo,
                                action = LibraryAction.Request(GalleryEmptyAction.REQUEST_DEVICE_ACCESS),
                            ),
                        )
                    },
                ),
            )
            if (protonAvailable || inputs.supportsDeviceTrash) add(
                LibrarySection(
                    key = "trash",
                    title = text.string(R.string.trash),
                    items = listOf(
                        entry(
                            key = "trash",
                            label = text.string(R.string.trash),
                            iconRes = R.drawable.ic_delete,
                            action = LibraryAction.Open(
                                GalleryDestination.Trash(
                                    if (protonAvailable) PhotoSource.PROTON else PhotoSource.DEVICE,
                                ),
                            ),
                        ),
                    ),
                ),
            )
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
            statusText = status(text.string(R.string.library), statusDetail),
        )
    }

    private fun entry(
        key: String,
        label: String,
        iconRes: Int,
        action: LibraryAction,
    ) = LibraryItem.Entry(key = key, label = label, iconRes = iconRes, action = action)

    private fun protonAlbum(
        inputs: GalleryUiInputs,
        destination: GalleryDestination.ProtonAlbumPhotos,
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
        val status = status(destination.album.name, statusDetail)
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
        val statusDetail = when {
            inputs.deviceTrash.isLoading -> text.string(R.string.loading_metadata)
            inputs.deviceTrash.errorMessage != null -> text.string(R.string.could_not_refresh)
            else -> photoCountStatus(assets.size)
        }
        val status = status(text.string(R.string.trash), statusDetail)
        val emptyState = if (assets.isEmpty() && !inputs.deviceTrash.isLoading) {
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
        val status = status(text.string(R.string.trash), statusDetail)
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
        ProtonAccountStatus.CONNECTING -> base(
            inputs = inputs,
            statusText = status(text.string(area), text.string(R.string.loading_metadata)),
            emptyState = loadingMetadataEmptyState(),
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
        GalleryDestination.Combined,
        GalleryDestination.ProtonTimeline,
        -> text.string(R.string.photos)

        is GalleryDestination.Device -> if (destination.collection == DeviceCollection.ALL) {
            text.string(R.string.photos)
        } else {
            text.string(destination.collection.labelRes)
        }

        is GalleryDestination.ProtonTag -> text.string(destination.tag.labelRes)
        GalleryDestination.Library -> text.string(R.string.library)
        is GalleryDestination.ProtonAlbumPhotos -> destination.album.name
        is GalleryDestination.Trash -> text.string(R.string.trash)
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

    private fun deviceAccessEmptyState() = GalleryEmptyState(
        title = text.string(R.string.allow_photo_access),
        message = text.string(R.string.allow_photo_access_message),
        actionLabel = text.string(R.string.allow_access),
        action = GalleryEmptyAction.REQUEST_DEVICE_ACCESS,
    )

    private fun loadingMetadataEmptyState() = GalleryEmptyState(
        title = text.string(R.string.loading_metadata),
        message = "",
    )

    private fun photoCountStatus(count: Int): String =
        text.quantity(R.plurals.photo_count, count, count)

    private fun albumCountStatus(count: Int): String =
        text.quantity(R.plurals.album_count, count, count)

    private fun status(label: String, detail: String): String =
        text.string(R.string.status_with_detail, label, detail)

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
