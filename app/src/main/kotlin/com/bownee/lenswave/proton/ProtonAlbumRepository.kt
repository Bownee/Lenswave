package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.NodeUid

@Singleton
internal class ProtonAlbumRepository @Inject constructor(
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonAlbumCache,
    private val snapshots: ProtonSnapshotCoordinator,
) {
    private val albumsSyncMutex = Mutex()
    private val albumPhotosSyncMutex = Mutex()
    private val mutableAlbumsState = MutableStateFlow(ProtonAlbumsState())
    private val mutableAlbumPhotosState = MutableStateFlow(ProtonAlbumPhotosState())

    val albumsState: StateFlow<ProtonAlbumsState> = mutableAlbumsState.asStateFlow()
    val albumPhotosState: StateFlow<ProtonAlbumPhotosState> = mutableAlbumPhotosState.asStateFlow()

    fun loadCached(userId: UserId) {
        emitAlbums(
            userId,
            cache.readAlbums(userId.id),
            syncing = false,
            hasLoaded = cache.hasAlbumsSnapshot(userId.id),
        )
    }

    fun loadCachedAlbum(userId: UserId, album: ProtonAlbumReference) {
        val photos = cache.readAlbumPhotos(userId.id, album.nodeUid)
        mutableAlbumPhotosState.value = ProtonAlbumPhotosState(
            userId = userId.id,
            albumUid = album.nodeUid,
            albumName = album.name,
            photos = photos,
            hasLoaded = cache.hasAlbumPhotosSnapshot(userId.id, album.nodeUid),
        )
    }

    suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) = albumsSyncMutex.withLock {
        val existing = cache.readAlbums(userId.id)
        val hasCachedSnapshot = cache.hasAlbumsSnapshot(userId.id)
        try {
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id,
                ProtonSyncSource.ALBUMS,
                ProtonSyncKeys.ALBUMS,
                forceRemote,
                hasCachedSnapshot,
            )
            if (!shouldEnumerate) {
                emitAlbums(userId, existing, syncing = false, hasLoaded = true)
                return@withLock
            }
            emitAlbums(userId, existing, syncing = true, hasLoaded = hasCachedSnapshot)
            val photosClient = clientProvider.get(userId)
            val sharedNodeUids = photosClient.enumerateSharedWithMeNodeUids().toList()
            val albums = (photosClient.enumerateAlbumNodeUids().toList() + sharedNodeUids)
                .distinctBy(NodeUid::value)
                .map { nodeUid -> loadAlbum(photosClient, userId, nodeUid) }
                .sortedByDescending(ProtonAlbum::lastActivityEpochSeconds)
                .toMutableList()
                .also { remoteAlbums ->
                    cache.reconcileAlbums(userId.id, remoteAlbums.map(ProtonAlbum::nodeUid))
                    cache.writeAlbums(userId.id, remoteAlbums)
                    snapshots.commit(userId.id, ProtonSyncKeys.ALBUMS)
                }

            emitAlbums(userId, albums, syncing = false)
        } catch (error: CancellationException) {
            mutableAlbumsState.value = mutableAlbumsState.value.copy(syncing = false)
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.ALBUM_SYNC, error)
            mutableAlbumsState.value = mutableAlbumsState.value.copy(
                syncing = false,
                refreshFailed = true,
            )
        }
    }

    suspend fun syncAlbumPhotoMetadata(
        userId: UserId,
        album: ProtonAlbumReference,
        forceRemote: Boolean,
    ) = albumPhotosSyncMutex.withLock {
        val existing = cache.readAlbumPhotos(userId.id, album.nodeUid)
        val hasCachedSnapshot = cache.hasAlbumPhotosSnapshot(userId.id, album.nodeUid)
        try {
            val syncKey = ProtonSyncKeys.albumPhotos(album.nodeUid)
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id,
                ProtonSyncSource.ALBUM_PHOTOS,
                syncKey,
                forceRemote,
                hasCachedSnapshot,
            )
            if (!shouldEnumerate) {
                emitAlbumPhotos(userId, album, existing, hasLoaded = true, syncing = false)
                return@withLock
            }
            emitAlbumPhotos(userId, album, existing, hasLoaded = hasCachedSnapshot, syncing = true)
            val photosClient = clientProvider.get(userId)
            val photos = photosClient.enumerateAlbum(NodeUid(album.nodeUid)).toList().map { item ->
                ProtonGalleryPhoto(
                    nodeUid = item.nodeUid.value,
                    captureTimeEpochSeconds = item.captureTime.epochSecond,
                    hasThumbnail = cache.thumbnailExists(userId.id, item.nodeUid.value),
                )
            }.sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
            cache.writeAlbumPhotos(userId.id, album.nodeUid, photos)
            snapshots.commit(userId.id, syncKey)
            emitAlbumPhotos(userId, album, photos, hasLoaded = true, syncing = false)
        } catch (error: CancellationException) {
            if (mutableAlbumPhotosState.value.albumUid == album.nodeUid) {
                mutableAlbumPhotosState.value = mutableAlbumPhotosState.value.copy(syncing = false)
            }
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.ALBUM_PHOTO_SYNC, error)
            if (mutableAlbumPhotosState.value.albumUid == album.nodeUid) {
                mutableAlbumPhotosState.value = mutableAlbumPhotosState.value.copy(
                    syncing = false,
                    refreshFailed = true,
                )
            }
        }
    }

    internal fun markCoverThumbnailsAvailable(userId: UserId, nodeUids: Set<String>) {
        markCoverThumbnails(userId, nodeUids, available = true)
    }

    internal fun markCoverThumbnailsUnavailable(userId: UserId, nodeUids: Set<String>) {
        markCoverThumbnails(userId, nodeUids, available = false)
    }

    internal fun markAlbumPhotoThumbnailsAvailable(userId: UserId, nodeUids: Set<String>) {
        markAlbumPhotoThumbnails(userId, nodeUids, available = true)
    }

    internal fun markAlbumPhotoThumbnailsUnavailable(userId: UserId, nodeUids: Set<String>) {
        markAlbumPhotoThumbnails(userId, nodeUids, available = false)
    }

    private fun markCoverThumbnails(userId: UserId, nodeUids: Set<String>, available: Boolean) {
        if (nodeUids.isEmpty()) return
        mutableAlbumsState.update { state ->
            if (state.userId != userId.id) return@update state
            val albums = state.albums.withThumbnailAvailability(
                nodeUids,
                available,
                nodeUid = ProtonAlbum::coverPhotoNodeUid,
                hasThumbnail = ProtonAlbum::hasCoverThumbnail,
                copy = { album, hasCoverThumbnail -> album.copy(hasCoverThumbnail = hasCoverThumbnail) },
            ) ?: return@update state
            state.copy(albums = albums)
        }
    }

    private fun markAlbumPhotoThumbnails(userId: UserId, nodeUids: Set<String>, available: Boolean) {
        if (nodeUids.isEmpty()) return
        mutableAlbumPhotosState.update { state ->
            if (state.userId != userId.id) return@update state
            val photos = state.photos.withThumbnailAvailability(
                nodeUids,
                available,
                nodeUid = ProtonGalleryPhoto::nodeUid,
                hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
                copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
            ) ?: return@update state
            state.copy(photos = photos)
        }
    }

    internal suspend fun removePhotos(userId: UserId, nodeUids: Set<String>) {
        albumsSyncMutex.withLock {
            albumPhotosSyncMutex.withLock albumPhotosLock@{
            if (nodeUids.isEmpty()) return@albumPhotosLock
            val current = mutableAlbumPhotosState.value
            current.albumUid?.let { albumUid ->
                val remaining = current.photos.filterNot { it.nodeUid in nodeUids }
                cache.writeAlbumPhotos(userId.id, albumUid, remaining)
                mutableAlbumPhotosState.value = current.copy(
                    photos = remaining,
                )
            }
            // ProtonPhotoCache removes the nodes from every album index and reconciles all counts.
            // Publish that complete snapshot so unopened albums cannot retain stale counts in memory.
            val updatedAlbums = cache.readAlbums(userId.id)
            mutableAlbumsState.value = mutableAlbumsState.value.copy(
                albums = updatedAlbums,
            )
            }
        }
    }

    internal fun reset() {
        mutableAlbumsState.value = ProtonAlbumsState()
        mutableAlbumPhotosState.value = ProtonAlbumPhotosState()
    }

    private suspend fun loadAlbum(
        photosClient: me.proton.drive.sdk.ProtonPhotosClient,
        userId: UserId,
        nodeUid: NodeUid,
    ): ProtonAlbum {
        val albumNode = requireNotNull(photosClient.getNode(nodeUid) as? AlbumNode) {
            "Proton returned no album node for ${nodeUid.value}"
        }
        return ProtonAlbum(
            nodeUid = albumNode.uid.value,
            // Keep the domain model locale-neutral; presentation supplies the fallback label.
            name = albumNode.name.getOrElse { "" },
            photoCount = albumNode.photoCount,
            coverPhotoNodeUid = albumNode.coverPhotoNodeUid?.value,
            createdAtEpochSeconds = albumNode.creationTime.epochSecond,
            lastActivityEpochSeconds = (albumNode.lastActivityTime ?: albumNode.creationTime).epochSecond,
            hasCoverThumbnail = albumNode.coverPhotoNodeUid?.let { coverUid ->
                cache.thumbnailExists(userId.id, coverUid.value)
            } == true,
            isShared = albumNode.isShared,
        )
    }

    private fun emitAlbums(
        userId: UserId,
        albums: List<ProtonAlbum>,
        syncing: Boolean,
        hasLoaded: Boolean = true,
    ) {
        mutableAlbumsState.value = ProtonAlbumsState(
            userId = userId.id,
            albums = albums.toList(),
            hasLoaded = hasLoaded,
            syncing = syncing,
        )
    }

    private fun emitAlbumPhotos(
        userId: UserId,
        album: ProtonAlbumReference,
        photos: List<ProtonGalleryPhoto>,
        hasLoaded: Boolean,
        syncing: Boolean,
    ) {
        val currentAlbumUid = mutableAlbumPhotosState.value.albumUid
        if (currentAlbumUid != null && currentAlbumUid != album.nodeUid) return
        mutableAlbumPhotosState.value = ProtonAlbumPhotosState(
            userId = userId.id,
            albumUid = album.nodeUid,
            albumName = album.name,
            photos = photos.toList(),
            hasLoaded = hasLoaded,
            syncing = syncing,
        )
    }
}
