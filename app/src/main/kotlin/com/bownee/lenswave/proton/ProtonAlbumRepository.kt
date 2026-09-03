package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics

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
    private val syncPipeline: ProtonPhotoSyncPipeline,
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
            downloadedThumbnailCount = photos.count(ProtonGalleryPhoto::hasThumbnail),
        )
    }

    suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) = albumsSyncMutex.withLock {
        val existing = cache.readAlbums(userId.id)
        val hasCachedSnapshot = cache.hasAlbumsSnapshot(userId.id)
        try {
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id,
                ProtonSyncSource.ALBUMS,
                ALBUMS_SYNC_KEY,
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
                    snapshots.commit(userId.id, ALBUMS_SYNC_KEY)
                }

            emitAlbums(userId, albums, syncing = false)
        } catch (error: CancellationException) {
            mutableAlbumsState.value = mutableAlbumsState.value.copy(syncing = false)
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("album-sync", error)
            mutableAlbumsState.value = mutableAlbumsState.value.copy(
                syncing = false,
                errorMessage = "Could not refresh Proton albums",
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
            val syncKey = "$ALBUM_PHOTOS_SYNC_KEY:${album.nodeUid}"
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
            val photos = syncPipeline.synchronizeMetadata(
                enumerate = {
                    photosClient.enumerateAlbum(NodeUid(album.nodeUid)).toList().map { item ->
                        ProtonGalleryPhoto(
                            nodeUid = item.nodeUid.value,
                            captureTimeEpochSeconds = item.captureTime.epochSecond,
                            hasThumbnail = cache.thumbnailExists(userId.id, item.nodeUid.value),
                        )
                    }.sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
                },
                commitSnapshot = { cache.writeAlbumPhotos(userId.id, album.nodeUid, it) },
                commitEnumeration = { snapshots.commit(userId.id, syncKey) },
            )
            emitAlbumPhotos(userId, album, photos, hasLoaded = true, syncing = false)
        } catch (error: CancellationException) {
            if (mutableAlbumPhotosState.value.albumUid == album.nodeUid) {
                mutableAlbumPhotosState.value = mutableAlbumPhotosState.value.copy(syncing = false)
            }
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("album-photo-sync", error)
            if (mutableAlbumPhotosState.value.albumUid == album.nodeUid) {
                mutableAlbumPhotosState.value = mutableAlbumPhotosState.value.copy(
                    syncing = false,
                    errorMessage = "Could not refresh this Proton album",
                )
            }
        }
    }

    internal fun markCoverThumbnailsAvailable(userId: UserId, nodeUids: Set<String>) {
        if (nodeUids.isEmpty()) return
        mutableAlbumsState.update { state ->
            if (state.userId != userId.id) return@update state
            var completedCoverCount = 0
            val albums = state.albums.map { album ->
                if (album.coverPhotoNodeUid !in nodeUids || album.hasCoverThumbnail) return@map album
                completedCoverCount++
                album.copy(hasCoverThumbnail = true)
            }
            if (completedCoverCount == 0) return@update state
            state.copy(
                albums = albums,
                downloadedCoverCount = state.downloadedCoverCount + completedCoverCount,
            )
        }
    }

    internal fun markCoverThumbnailsUnavailable(userId: UserId, nodeUids: Set<String>) {
        if (nodeUids.isEmpty()) return
        mutableAlbumsState.update { state ->
            if (state.userId != userId.id) return@update state
            var invalidatedCount = 0
            val albums = state.albums.map { album ->
                if (album.coverPhotoNodeUid !in nodeUids || !album.hasCoverThumbnail) return@map album
                invalidatedCount++
                album.copy(hasCoverThumbnail = false)
            }
            if (invalidatedCount == 0) return@update state
            state.copy(
                albums = albums,
                downloadedCoverCount = (state.downloadedCoverCount - invalidatedCount).coerceAtLeast(0),
            )
        }
    }

    internal fun markAlbumPhotoThumbnailsAvailable(userId: UserId, nodeUids: Set<String>) {
        if (nodeUids.isEmpty()) return
        mutableAlbumPhotosState.update { state ->
            if (state.userId != userId.id) return@update state
            var completedCount = 0
            val photos = state.photos.map { photo ->
                if (photo.nodeUid !in nodeUids || photo.hasThumbnail) return@map photo
                completedCount++
                photo.copy(hasThumbnail = true)
            }
            if (completedCount == 0) return@update state
            state.copy(
                photos = photos,
                downloadedThumbnailCount = state.downloadedThumbnailCount + completedCount,
            )
        }
    }

    internal fun markAlbumPhotoThumbnailsUnavailable(userId: UserId, nodeUids: Set<String>) {
        if (nodeUids.isEmpty()) return
        mutableAlbumPhotosState.update { state ->
            if (state.userId != userId.id) return@update state
            var invalidatedCount = 0
            val photos = state.photos.map { photo ->
                if (photo.nodeUid !in nodeUids || !photo.hasThumbnail) return@map photo
                invalidatedCount++
                photo.copy(hasThumbnail = false)
            }
            if (invalidatedCount == 0) return@update state
            state.copy(
                photos = photos,
                downloadedThumbnailCount = (state.downloadedThumbnailCount - invalidatedCount).coerceAtLeast(0),
            )
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
                    downloadedThumbnailCount = remaining.count(ProtonGalleryPhoto::hasThumbnail),
                )
            }
            // ProtonPhotoCache removes the nodes from every album index and reconciles all counts.
            // Publish that complete snapshot so unopened albums cannot retain stale counts in memory.
            val updatedAlbums = cache.readAlbums(userId.id)
            mutableAlbumsState.value = mutableAlbumsState.value.copy(
                albums = updatedAlbums,
                downloadedCoverCount = updatedAlbums.count {
                    it.coverPhotoNodeUid != null && it.hasCoverThumbnail
                },
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
            downloadedCoverCount = albums.count { it.coverPhotoNodeUid != null && it.hasCoverThumbnail },
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
            downloadedThumbnailCount = photos.count(ProtonGalleryPhoto::hasThumbnail),
        )
    }

    private companion object {
        const val ALBUMS_SYNC_KEY = "albums"
        const val ALBUM_PHOTOS_SYNC_KEY = "album-photos"
    }
}
