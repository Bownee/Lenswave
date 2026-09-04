package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.NodeUid
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ProtonAlbumRepository
    @Inject
    constructor(
        private val clientProvider: ProtonPhotosClientProvider,
        private val cache: ProtonAlbumCache,
        private val snapshotSync: ProtonSnapshotSync,
    ) {
        private val albumsSyncMutex = Mutex()
        private val albumPhotosSyncMutex = Mutex()
        private val mutableAlbumsState = MutableStateFlow(ProtonAlbumsState())
        private val mutableAlbumPhotosState = MutableStateFlow(ProtonAlbumPhotosState())

        val albumsState: StateFlow<ProtonAlbumsState> = mutableAlbumsState.asStateFlow()
        val albumPhotosState: StateFlow<ProtonAlbumPhotosState> = mutableAlbumPhotosState.asStateFlow()

        fun loadCached(userId: UserId) {
            val albums = cache.readAlbumsSnapshot(userId.id)
            emitAlbums(
                userId,
                albums.orEmpty(),
                syncing = false,
                hasLoaded = albums != null,
            )
        }

        fun loadCachedAlbum(
            userId: UserId,
            album: ProtonAlbumReference,
        ) {
            val photos = cache.readAlbumPhotosSnapshot(userId.id, album.nodeUid)
            mutableAlbumPhotosState.value =
                ProtonAlbumPhotosState(
                    userId = userId.id,
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = photos.orEmpty(),
                    hasLoaded = photos != null,
                )
        }

        suspend fun syncMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) = albumsSyncMutex.withLock {
            // A loaded state is the cached listing plus every mark since; re-reading the index
            // only to learn that costs a decrypt and a parse the sync policy often discards.
            val current = mutableAlbumsState.value
            val (existing, hasCachedSnapshot) =
                if (current.userId == userId.id && current.hasLoaded) {
                    current.albums to true
                } else {
                    val cached = cache.readAlbumsSnapshot(userId.id)
                    cached.orEmpty() to (cached != null)
                }
            snapshotSync.sync(
                userId = userId.id,
                source = ProtonSyncSource.ALBUMS,
                syncKey = ProtonSyncKeys.ALBUMS,
                forceRemote = forceRemote,
                hasSnapshot = hasCachedSnapshot,
                operation = LenswaveOperation.ALBUM_SYNC.tag,
                publishFresh = { emitAlbums(userId, existing, syncing = false, hasLoaded = true) },
                publishSyncing = { emitAlbums(userId, existing, syncing = true, hasLoaded = hasCachedSnapshot) },
                enumerate = {
                    val photosClient = clientProvider.get(userId)
                    val sharedNodeUids = photosClient.enumerateSharedWithMeNodeUids().toList()
                    val albumNodeUids =
                        (photosClient.enumerateAlbumNodeUids().toList() + sharedNodeUids).distinctBy(NodeUid::value)
                    // Each album is one round trip; a few in flight at a time keeps the listing
                    // from growing linearly with the album count without flooding the SDK.
                    val inFlight = Semaphore(ALBUM_LOAD_PARALLELISM)
                    coroutineScope {
                        albumNodeUids
                            .map { nodeUid ->
                                async { inFlight.withPermit { loadAlbum(photosClient, userId, nodeUid) } }
                            }.awaitAll()
                    }.sortedByDescending(ProtonAlbum::lastActivityEpochSeconds)
                },
                commit = { albums ->
                    cache.reconcileAlbums(userId.id, albums.map(ProtonAlbum::nodeUid))
                    cache.writeAlbums(userId.id, albums)
                },
                publishResult = { albums -> emitAlbums(userId, albums, syncing = false) },
                publishCancelled = { mutableAlbumsState.value = mutableAlbumsState.value.copy(syncing = false) },
                publishFailed = {
                    mutableAlbumsState.value =
                        mutableAlbumsState.value.copy(
                            syncing = false,
                            refreshFailed = true,
                        )
                },
            )
        }

        suspend fun syncAlbumPhotoMetadata(
            userId: UserId,
            album: ProtonAlbumReference,
            forceRemote: Boolean,
        ) = albumPhotosSyncMutex.withLock {
            val current = mutableAlbumPhotosState.value
            val (existing, hasCachedSnapshot) =
                if (current.userId == userId.id && current.albumUid == album.nodeUid && current.hasLoaded) {
                    current.photos to true
                } else {
                    val cached = cache.readAlbumPhotosSnapshot(userId.id, album.nodeUid)
                    cached.orEmpty() to (cached != null)
                }
            snapshotSync.sync(
                userId = userId.id,
                source = ProtonSyncSource.ALBUM_PHOTOS,
                syncKey = ProtonSyncKeys.albumPhotos(album.nodeUid),
                forceRemote = forceRemote,
                hasSnapshot = hasCachedSnapshot,
                operation = LenswaveOperation.ALBUM_PHOTO_SYNC.tag,
                publishFresh = { emitAlbumPhotos(userId, album, existing, hasLoaded = true, syncing = false) },
                publishSyncing = {
                    emitAlbumPhotos(userId, album, existing, hasLoaded = hasCachedSnapshot, syncing = true)
                },
                enumerate = {
                    val items = clientProvider.get(userId).enumerateAlbum(NodeUid(album.nodeUid)).toList()
                    val availability = cache.storedRenditions(userId.id)
                    items
                        .map { item -> availability.photo(item.nodeUid.value, item.captureTime.epochSecond) }
                        .sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
                },
                commit = { photos -> cache.writeAlbumPhotos(userId.id, album.nodeUid, photos) },
                publishResult = { photos ->
                    emitAlbumPhotos(userId, album, photos, hasLoaded = true, syncing = false)
                },
                // The album-photo state may already belong to another album by the time the sync
                // settles; only the album that is still open reflects the outcome.
                publishCancelled = {
                    if (mutableAlbumPhotosState.value.albumUid == album.nodeUid) {
                        mutableAlbumPhotosState.value = mutableAlbumPhotosState.value.copy(syncing = false)
                    }
                },
                publishFailed = {
                    if (mutableAlbumPhotosState.value.albumUid == album.nodeUid) {
                        mutableAlbumPhotosState.value =
                            mutableAlbumPhotosState.value.copy(
                                syncing = false,
                                refreshFailed = true,
                            )
                    }
                },
            )
        }

        internal fun markCoverThumbnailsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markCoverThumbnails(userId, nodeUids, available = true)
        }

        internal fun markCoverThumbnailsUnavailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markCoverThumbnails(userId, nodeUids, available = false)
        }

        internal fun markAlbumPhotoThumbnailsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markAlbumPhotoThumbnails(userId, nodeUids, available = true)
        }

        internal fun markAlbumPhotoThumbnailsUnavailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markAlbumPhotoThumbnails(userId, nodeUids, available = false)
        }

        private fun markCoverThumbnails(
            userId: UserId,
            nodeUids: Set<String>,
            available: Boolean,
        ) {
            if (nodeUids.isEmpty()) return
            mutableAlbumsState.update { state ->
                if (state.userId != userId.id) return@update state
                val albums =
                    state.albums.withThumbnailAvailability(
                        nodeUids,
                        available,
                        nodeUid = ProtonAlbum::coverPhotoNodeUid,
                        hasThumbnail = ProtonAlbum::hasCoverThumbnail,
                        copy = { album, hasCoverThumbnail -> album.copy(hasCoverThumbnail = hasCoverThumbnail) },
                    ) ?: return@update state
                state.copy(albums = albums)
            }
        }

        private fun markAlbumPhotoThumbnails(
            userId: UserId,
            nodeUids: Set<String>,
            available: Boolean,
        ) {
            if (nodeUids.isEmpty()) return
            mutableAlbumPhotosState.update { state ->
                if (state.userId != userId.id) return@update state
                val photos =
                    state.photos.withThumbnailAvailability(
                        nodeUids,
                        available,
                        nodeUid = ProtonGalleryPhoto::nodeUid,
                        hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
                        copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
                    ) ?: return@update state
                state.copy(photos = photos)
            }
        }

        internal suspend fun removePhotos(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            albumsSyncMutex.withLock {
                albumPhotosSyncMutex.withLock albumPhotosLock@{
                    if (nodeUids.isEmpty()) return@albumPhotosLock
                    val current = mutableAlbumPhotosState.value
                    current.albumUid?.let { albumUid ->
                        val remaining = current.photos.filterNot { it.nodeUid in nodeUids }
                        cache.writeAlbumPhotos(userId.id, albumUid, remaining)
                        mutableAlbumPhotosState.value =
                            current.copy(
                                photos = remaining,
                            )
                    }
                    // ProtonPhotoCache removes the nodes from every album index and reconciles all counts.
                    // Publish that complete snapshot so unopened albums cannot retain stale counts in memory.
                    val updatedAlbums = cache.readAlbumsSnapshot(userId.id).orEmpty()
                    mutableAlbumsState.value =
                        mutableAlbumsState.value.copy(
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
            val albumNode =
                requireNotNull(photosClient.getNode(nodeUid) as? AlbumNode) {
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
                hasCoverThumbnail =
                    albumNode.coverPhotoNodeUid?.let { coverUid ->
                        cache.thumbnailExists(userId.id, coverUid.value)
                    } == true,
                isShared = albumNode.isShared,
            )
        }

        /**
         * The published list keeps its previous instance whenever the content is unchanged; the
         * gallery memoizes by list identity, so a syncing heartbeat must not hand it an equal copy.
         * Callers hand over lists they built themselves, so no defensive copy is taken.
         */
        private fun emitAlbums(
            userId: UserId,
            albums: List<ProtonAlbum>,
            syncing: Boolean,
            hasLoaded: Boolean = true,
        ) {
            mutableAlbumsState.update { previous ->
                ProtonAlbumsState(
                    userId = userId.id,
                    albums = if (previous.albums == albums) previous.albums else albums,
                    hasLoaded = hasLoaded,
                    syncing = syncing,
                )
            }
        }

        private fun emitAlbumPhotos(
            userId: UserId,
            album: ProtonAlbumReference,
            photos: List<ProtonGalleryPhoto>,
            hasLoaded: Boolean,
            syncing: Boolean,
        ) {
            mutableAlbumPhotosState.update { previous ->
                val currentAlbumUid = previous.albumUid
                if (currentAlbumUid != null && currentAlbumUid != album.nodeUid) return@update previous
                ProtonAlbumPhotosState(
                    userId = userId.id,
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = if (previous.photos == photos) previous.photos else photos,
                    hasLoaded = hasLoaded,
                    syncing = syncing,
                )
            }
        }

        private companion object {
            const val ALBUM_LOAD_PARALLELISM = 4
        }
    }
