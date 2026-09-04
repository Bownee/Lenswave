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
        /** One albums sync and one album-photo sync at a time; held across their enumerations. */
        private val albumsSyncMutex = Mutex()
        private val albumPhotosSyncMutex = Mutex()

        /**
         * Serializes every write to the album files and to the states that mirror them: a sync
         * commit (with its stamp and publish) and a removal. It is the innermost lock, taken
         * after a sync mutex and never held across an enumeration, so a trash never waits on a
         * network round trip; a sync that enumerated before the trash narrows its listing at
         * commit time instead (see [ProtonPhotoReconciliation.withoutRemovedSince]).
         */
        private val mutationMutex = Mutex()

        /** Uid lookups over the published albums (by cover) and album photos, memoized per list instance. */
        private val albumCoverIndex = ProtonNodeUidIndex(ProtonAlbum::coverPhotoNodeUid)
        private val albumPhotoIndex = ProtonNodeUidIndex(ProtonGalleryPhoto::nodeUid)
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
                    ProtonReconcileSafetyPolicy.requireCommit(
                        listing = "albums",
                        existing = existing,
                        remoteNodeUids = albums.mapTo(HashSet(), ProtonAlbum::nodeUid),
                        forceRemote = forceRemote,
                        nodeUid = ProtonAlbum::nodeUid,
                    )
                    // The listing lands before the vanished albums' photo indexes are deleted,
                    // so a crash in between leaves a stray index rather than a listing whose
                    // albums have no index.
                    cache.writeAlbums(userId.id, albums)
                    cache.reconcileAlbums(userId.id, albums.map(ProtonAlbum::nodeUid))
                    albums
                },
                publishResult = { albums -> emitAlbums(userId, albums, syncing = false) },
                publishCancelled = { updateAlbums(userId) { state -> state.copy(syncing = false) } },
                publishFailed = { updateAlbums(userId) { state -> state.copy(syncing = false, refreshFailed = true) } },
                commitGate = { commit -> mutationMutex.withLock { commit() } },
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
                commit = { photos ->
                    ProtonReconcileSafetyPolicy.requireCommit(
                        listing = "album-photos",
                        existing = existing,
                        remoteNodeUids = photos.mapTo(HashSet(), ProtonGalleryPhoto::nodeUid),
                        forceRemote = forceRemote,
                        nodeUid = ProtonGalleryPhoto::nodeUid,
                    )
                    // A photo trashed while the album was enumerating has left the published
                    // listing; the enumerated one must not bring it back.
                    val retained =
                        ProtonPhotoReconciliation.withoutRemovedSince(
                            enumerated = photos,
                            existing = existing,
                            published = publishedAlbumPhotos(userId, album),
                            nodeUid = ProtonGalleryPhoto::nodeUid,
                        )
                    cache.writeAlbumPhotos(userId.id, album.nodeUid, retained)
                    retained
                },
                publishResult = { photos ->
                    emitAlbumPhotos(userId, album, photos, hasLoaded = true, syncing = false)
                },
                // The album-photo state may already belong to another album by the time the sync
                // settles; only the album that is still open reflects the outcome.
                publishCancelled = {
                    updateAlbumPhotos(userId, album.nodeUid) { state -> state.copy(syncing = false) }
                },
                publishFailed = {
                    updateAlbumPhotos(userId, album.nodeUid) { state ->
                        state.copy(syncing = false, refreshFailed = true)
                    }
                },
                commitGate = { commit -> mutationMutex.withLock { commit() } },
            )
        }

        /** The album-photo listing on screen, when it is still [album]'s; null when another album (or account) took over. */
        private fun publishedAlbumPhotos(
            userId: UserId,
            album: ProtonAlbumReference,
        ): List<ProtonGalleryPhoto>? =
            mutableAlbumPhotosState.value
                .takeIf { state -> state.userId == userId.id && state.albumUid == album.nodeUid }
                ?.photos

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
            val current = mutableAlbumsState.value
            if (current.userId != userId.id || !current.albums.containsAnyNodeUid(nodeUids, albumCoverIndex)) return
            mutableAlbumsState.update { state ->
                if (state.userId != userId.id) return@update state
                val albums =
                    state.albums.withThumbnailAvailability(
                        nodeUids,
                        available,
                        albumCoverIndex,
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
            val current = mutableAlbumPhotosState.value
            if (current.userId != userId.id || !current.photos.containsAnyNodeUid(nodeUids, albumPhotoIndex)) return
            mutableAlbumPhotosState.update { state ->
                if (state.userId != userId.id) return@update state
                val photos =
                    state.photos.withThumbnailAvailability(
                        nodeUids,
                        available,
                        albumPhotoIndex,
                        hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
                        copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
                    ) ?: return@update state
                state.copy(photos = photos)
            }
        }

        /**
         * Applies a trash Proton has accepted to every album index and to the album on screen.
         * Only the mutation mutex is taken: a sync enumerating right now keeps running and
         * narrows its listing when it commits, so the trash never waits on the network.
         */
        internal suspend fun removePhotos(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            if (nodeUids.isEmpty()) return
            mutationMutex.withLock {
                cache.removeAlbumPhotos(userId.id, nodeUids)
                mutableAlbumPhotosState.update { state ->
                    if (state.userId != userId.id) return@update state
                    val remaining = state.photos.filterNot { it.nodeUid in nodeUids }
                    if (remaining.size == state.photos.size) state else state.copy(photos = remaining)
                }
                // The cache reconciled every album count; publish that snapshot so unopened albums
                // cannot keep stale counts in memory. One that cannot be read right now keeps the
                // published list: a blank Albums tab over a transient read failure would be worse
                // than a stale count.
                cache.readAlbumsSnapshot(userId.id)?.let { updatedAlbums ->
                    updateAlbums(userId) { state -> state.copy(albums = updatedAlbums) }
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

        /** Applies [transform] to the published albums, but only while they still belong to [userId]. */
        private inline fun updateAlbums(
            userId: UserId,
            transform: (ProtonAlbumsState) -> ProtonAlbumsState,
        ) {
            mutableAlbumsState.update { state -> if (state.userId != userId.id) state else transform(state) }
        }

        /**
         * Applies [transform] to the published album photos while they are still [userId]'s and
         * still [albumUid]'s: the state may belong to another album by the time a sync settles.
         */
        private inline fun updateAlbumPhotos(
            userId: UserId,
            albumUid: String,
            transform: (ProtonAlbumPhotosState) -> ProtonAlbumPhotosState,
        ) {
            mutableAlbumPhotosState.update { state ->
                if (state.userId != userId.id || state.albumUid != albumUid) state else transform(state)
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
