package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.NodeUid

@Singleton
internal class ProtonAlbumRepository @Inject constructor(
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonAlbumCache,
    private val downloads: ProtonDownloadRepository,
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
        emitAlbums(userId, cache.readAlbums(userId.id), syncing = false)
    }

    fun loadCachedAlbum(userId: UserId, album: ProtonAlbumReference) {
        val photos = cache.readAlbumPhotos(userId.id, album.nodeUid)
        mutableAlbumPhotosState.value = ProtonAlbumPhotosState(
            userId = userId.id,
            albumUid = album.nodeUid,
            albumName = album.name,
            photos = photos,
            downloadedThumbnailCount = photos.count(ProtonGalleryPhoto::hasThumbnail),
        )
    }

    suspend fun syncAlbums(
        userId: UserId,
        forceRemote: Boolean,
        maxThumbnailDownloads: Int? = null,
    ) = albumsSyncMutex.withLock {
        val existing = cache.readAlbums(userId.id)
        emitAlbums(userId, existing, syncing = true)
        try {
            val photosClient = clientProvider.get(userId)
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id, ProtonSyncSource.ALBUMS, ALBUMS_SYNC_KEY, forceRemote, cache.hasAlbumsSnapshot(userId.id),
            )
            val albums = if (shouldEnumerate) {
                val sharedNodeUids = photosClient.enumerateSharedWithMeNodeUids().toList()
                (photosClient.enumerateAlbumNodeUids().toList() + sharedNodeUids)
                    .distinctBy(NodeUid::value)
                    .map { nodeUid -> loadAlbum(photosClient, userId, nodeUid) }
                    .sortedByDescending(ProtonAlbum::lastActivityEpochSeconds)
                    .toMutableList()
                    .also { remoteAlbums ->
                        cache.reconcileAlbums(userId.id, remoteAlbums.map(ProtonAlbum::nodeUid))
                        cache.writeAlbums(userId.id, remoteAlbums)
                        snapshots.commit(userId.id, ALBUMS_SYNC_KEY)
                    }
            } else {
                existing.toMutableList()
            }

            emitAlbums(userId, albums, syncing = true)
            val albumPositionsByCover = albums.indices
                .filter { albums[it].coverPhotoNodeUid != null }
                .groupBy { requireNotNull(albums[it].coverPhotoNodeUid) }
            downloads.downloadMissingThumbnails(
                photosClient = photosClient,
                userId = userId,
                nodeUids = albumPositionsByCover.keys.filterNot {
                    cache.thumbnailIsDecodable(userId.id, it)
                },
                maxDownloads = maxThumbnailDownloads,
                onStored = { nodeUid ->
                    albumPositionsByCover[nodeUid].orEmpty().forEach { position ->
                        albums[position] = albums[position].copy(hasCoverThumbnail = true)
                    }
                },
                onProgress = { emitAlbums(userId, albums, syncing = true) },
            )
            cache.writeAlbums(userId.id, albums)
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

    suspend fun syncAlbumPhotos(
        userId: UserId,
        album: ProtonAlbumReference,
        forceRemote: Boolean,
    ) = albumPhotosSyncMutex.withLock {
        val existing = cache.readAlbumPhotos(userId.id, album.nodeUid)
        emitAlbumPhotos(userId, album, existing, syncing = true)
        try {
            val photosClient = clientProvider.get(userId)
            val syncKey = "$ALBUM_PHOTOS_SYNC_KEY:${album.nodeUid}"
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id,
                ProtonSyncSource.ALBUM_PHOTOS,
                syncKey,
                forceRemote,
                cache.hasAlbumPhotosSnapshot(userId.id, album.nodeUid),
            )
            val photos = syncPipeline.synchronize(
                photosClient = photosClient,
                userId = userId,
                existing = existing,
                shouldEnumerate = shouldEnumerate,
                enumerate = {
                    photosClient.enumerateAlbum(NodeUid(album.nodeUid)).toList().map { item ->
                        ProtonGalleryPhoto(
                            nodeUid = item.nodeUid.value,
                            captureTimeEpochSeconds = item.captureTime.epochSecond,
                            hasThumbnail = cache.thumbnailIsDecodable(userId.id, item.nodeUid.value),
                        )
                    }.sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
                },
                commitSnapshot = { cache.writeAlbumPhotos(userId.id, album.nodeUid, it) },
                commitEnumeration = { snapshots.commit(userId.id, syncKey) },
                onProgress = { emitAlbumPhotos(userId, album, it, syncing = true) },
            )
            emitAlbumPhotos(userId, album, photos, syncing = false)
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
                cache.thumbnailIsDecodable(userId.id, coverUid.value)
            } == true,
            isShared = albumNode.isShared,
        )
    }

    private fun emitAlbums(userId: UserId, albums: List<ProtonAlbum>, syncing: Boolean) {
        mutableAlbumsState.value = ProtonAlbumsState(
            userId = userId.id,
            albums = albums.toList(),
            syncing = syncing,
            downloadedCoverCount = albums.count { it.coverPhotoNodeUid != null && it.hasCoverThumbnail },
        )
    }

    private fun emitAlbumPhotos(
        userId: UserId,
        album: ProtonAlbumReference,
        photos: List<ProtonGalleryPhoto>,
        syncing: Boolean,
    ) {
        val currentAlbumUid = mutableAlbumPhotosState.value.albumUid
        if (currentAlbumUid != null && currentAlbumUid != album.nodeUid) return
        mutableAlbumPhotosState.value = ProtonAlbumPhotosState(
            userId = userId.id,
            albumUid = album.nodeUid,
            albumName = album.name,
            photos = photos.toList(),
            syncing = syncing,
            downloadedThumbnailCount = photos.count(ProtonGalleryPhoto::hasThumbnail),
        )
    }

    private companion object {
        const val ALBUMS_SYNC_KEY = "albums"
        const val ALBUM_PHOTOS_SYNC_KEY = "album-photos"
    }
}
