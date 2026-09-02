package com.bownee.lenswave.proton

import java.io.File
import com.bownee.lenswave.gallery.ProtonDuplicateSource
import com.bownee.lenswave.gallery.ProtonGalleryReader
import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid

/**
 * Application gateway for Proton Photos capabilities. Focused repositories own synchronization,
 * albums, trash, and downloads; this class enforces the active-session boundary around them.
 */
@Singleton
class ProtonPhotoGateway @Inject internal constructor(
    private val timeline: ProtonTimelineRepository,
    private val albums: ProtonAlbumRepository,
    private val trash: ProtonTrashRepository,
    private val downloads: ProtonDownloadRepository,
    private val thumbnailQueue: ProtonThumbnailQueue,
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonSessionCache,
    private val sessionGuard: ProtonSessionGuard,
) : ProtonGalleryReader, ProtonSessionLifecycle, ProtonDuplicateSource {
    private val metadataSyncMutex = Mutex()
    private val mutableMetadataState = MutableStateFlow(ProtonMetadataState())

    override val state: StateFlow<ProtonGalleryState> = timeline.state
    override val metadataState: StateFlow<ProtonMetadataState> = mutableMetadataState.asStateFlow()
    override val albumsState: StateFlow<ProtonAlbumsState> = albums.albumsState
    override val albumPhotosState: StateFlow<ProtonAlbumPhotosState> = albums.albumPhotosState
    override val trashState: StateFlow<ProtonTrashState> = trash.state

    override suspend fun activate(userId: UserId) {
        withContext(Dispatchers.IO) { sessionGuard.activate(userId) { previousUserId ->
            previousUserId?.let { previous ->
                clientProvider.disconnect(previous)
                cache.clearUser(previous.id)
            }
            timeline.reset()
            albums.reset()
            trash.reset()
            cache.trimUser(userId.id)
            timeline.loadCached(userId)
            albums.loadCached(userId)
            trash.loadCached(userId)
            mutableMetadataState.value = metadataSnapshot(userId, isLoading = false)
        } }
    }

    override suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                metadataSyncMutex.withLock {
                    mutableMetadataState.value = metadataSnapshot(userId, isLoading = true)
                    try {
                        coroutineScope {
                            listOf(
                                async { timeline.syncMetadata(userId, forceRemote) },
                                async { albums.syncMetadata(userId, forceRemote) },
                                async { trash.syncMetadata(userId, forceRemote) },
                            ).awaitAll()
                        }
                        reconcileThumbnailQueue(userId)
                    } finally {
                        mutableMetadataState.value = metadataSnapshot(userId, isLoading = false)
                    }
                }
            }
        }
    }

    override suspend fun loadCachedAlbum(userId: UserId, album: ProtonAlbumReference) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                albums.loadCachedAlbum(userId, album)
                reconcileAlbumThumbnailQueue(userId, album)
            }
        }
    }

    override suspend fun syncAlbumPhotoMetadata(
        userId: UserId,
        album: ProtonAlbumReference,
        forceRemote: Boolean,
    ) {
        withContext(Dispatchers.IO) { sessionGuard.withActiveSession(userId) {
            albums.syncAlbumPhotoMetadata(userId, album, forceRemote)
            reconcileAlbumThumbnailQueue(userId, album)
        } }
    }

    override suspend fun prioritizeThumbnailSection(userId: UserId, nodeUids: Collection<String>) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                thumbnailQueue.prioritizeSection(userId.id, nodeUids)
            }
        }
    }

    override suspend fun prioritizeVisibleThumbnails(userId: UserId, nodeUids: Collection<String>) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                thumbnailQueue.prioritizeVisible(userId.id, nodeUids)
            }
        }
    }

    internal suspend fun downloadNextQueuedThumbnail(userId: UserId): ProtonThumbnailQueueStep =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                val entry = thumbnailQueue.nextReady(userId.id)
                    ?: return@withActiveSession ProtonThumbnailQueueStep.Idle(
                        thumbnailQueue.hasPending(userId.id)
                    )
                try {
                    downloads.downloadThumbnail(userId, entry.nodeUid)
                    if (thumbnailQueue.complete(userId.id, entry.nodeUid)) {
                        if (TIMELINE_QUEUE_SOURCE in entry.sources) {
                            timeline.markThumbnailAvailable(userId, entry.nodeUid)
                        }
                        if (ALBUM_COVERS_QUEUE_SOURCE in entry.sources) {
                            albums.markCoverThumbnailAvailable(userId, entry.nodeUid)
                        }
                        if (TRASH_QUEUE_SOURCE in entry.sources) {
                            trash.markThumbnailAvailable(userId, entry.nodeUid)
                        }
                        if (entry.sources.any { source -> source.startsWith("$ALBUM_PHOTOS_QUEUE_SOURCE:") }) {
                            albums.markAlbumPhotoThumbnailAvailable(userId, entry.nodeUid)
                        }
                    } else {
                        downloads.removeThumbnail(userId, entry.nodeUid)
                    }
                    ProtonThumbnailQueueStep.Downloaded
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    thumbnailQueue.defer(userId.id, entry.nodeUid)
                    ProtonThumbnailQueueStep.Failed
                }
            }
        }

    internal suspend fun flushThumbnailQueue(userId: UserId) {
        thumbnailQueue.flush(userId.id)
    }

    suspend fun downloadOriginal(userId: UserId, nodeUid: String): File =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { downloads.downloadOriginal(userId, nodeUid) }
        }

    override suspend fun getOriginalFileName(userId: UserId, nodeUid: String): String? =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { downloads.getOriginalFileName(userId, nodeUid) }
        }

    fun readThumbnail(userId: UserId, nodeUid: String): ByteArray? =
        downloads.readThumbnail(userId, nodeUid)

    override suspend fun findPhotoDuplicates(
        userId: UserId,
        name: String,
        generateSha1: suspend () -> ByteArray,
    ): List<String> = withContext(Dispatchers.IO) {
        sessionGuard.withActiveSession(userId) {
            downloads.findPhotoDuplicates(userId, name, generateSha1)
        }
    }

    suspend fun trashPhotos(userId: UserId, nodeUids: Collection<String>): ProtonTrashResult {
        return withContext(Dispatchers.IO) { sessionGuard.withActiveSession(userId) {
            val requested = nodeUids.distinct()
            if (requested.isEmpty()) return@withActiveSession ProtonTrashResult()
            val results = clientProvider.get(userId).trashNodes(requested.map(::NodeUid)).toList()
            val successful = results.filterIsInstance<NodeResultPair.Success>()
                .map { it.nodeUid.value }
                .toSet()
            if (successful.isNotEmpty()) {
                timeline.removePhotos(userId, successful)
                albums.removePhotos(userId, successful)
                cache.writeLastSuccessfulSync(userId.id, TRASH_SYNC_KEY, 0L)
            }
            ProtonTrashResult(
                trashedCount = successful.size,
                failedCount = results.count { it is NodeResultPair.Failure },
            )
        } }
    }

    suspend fun deletePhotosPermanently(
        userId: UserId,
        nodeUids: Collection<String>,
    ): ProtonDeleteResult = withContext(Dispatchers.IO) {
        sessionGuard.withActiveSession(userId) { trash.deletePermanently(userId, nodeUids) }
    }

    override suspend fun disconnect(userId: UserId) {
        withContext(Dispatchers.IO) { sessionGuard.disconnect(userId) { wasActive ->
            clientProvider.disconnect(userId)
            cache.clearUser(userId.id)
            thumbnailQueue.forget(userId.id)
            if (wasActive) {
                timeline.reset()
                albums.reset()
                trash.reset()
                mutableMetadataState.value = ProtonMetadataState()
            }
        } }
    }

    fun isActive(userId: UserId): Boolean = sessionGuard.isActive(userId)

    internal fun updateThumbnailWorkStatus(status: ProtonThumbnailWorkStatus?) {
        timeline.updateThumbnailWorkStatus(status)
    }

    private fun metadataSnapshot(userId: UserId, isLoading: Boolean): ProtonMetadataState {
        val timelineState = timeline.state.value
        val albumsState = albums.albumsState.value
        val trashState = trash.state.value
        val hasLoaded = timelineState.userId == userId.id && timelineState.hasLoaded &&
            albumsState.userId == userId.id && albumsState.hasLoaded &&
            trashState.userId == userId.id && trashState.hasLoaded
        return ProtonMetadataState(
            userId = userId.id,
            isLoading = isLoading,
            hasLoaded = hasLoaded,
            errorMessage = listOfNotNull(
                timelineState.errorMessage,
                albumsState.errorMessage,
                trashState.errorMessage,
            ).firstOrNull(),
        )
    }

    private suspend fun reconcileThumbnailQueue(userId: UserId) {
        val pendingNodeUidsBySource = linkedMapOf<String, Collection<String>>()
        timeline.state.value.takeIf { it.userId == userId.id && it.hasLoaded }?.let { state ->
            pendingNodeUidsBySource[TIMELINE_QUEUE_SOURCE] =
                state.photos.filterNot(ProtonGalleryPhoto::hasThumbnail).map(ProtonGalleryPhoto::nodeUid)
        }
        val albumState = albums.albumsState.value.takeIf { it.userId == userId.id && it.hasLoaded }
        albumState?.let { state ->
            pendingNodeUidsBySource[ALBUM_COVERS_QUEUE_SOURCE] = state.albums
                .filterNot(ProtonAlbum::hasCoverThumbnail)
                .mapNotNull(ProtonAlbum::coverPhotoNodeUid)
        }
        trash.state.value.takeIf { it.userId == userId.id && it.hasLoaded }?.let { state ->
            pendingNodeUidsBySource[TRASH_QUEUE_SOURCE] =
                state.photos.filterNot(ProtonTrashPhoto::hasThumbnail).map(ProtonTrashPhoto::nodeUid)
        }
        thumbnailQueue.replaceSources(
            userId.id,
            pendingNodeUidsBySource,
            albumState?.albums?.map(ProtonAlbum::nodeUid),
        )
    }

    private suspend fun reconcileAlbumThumbnailQueue(userId: UserId, album: ProtonAlbumReference) {
        albums.albumPhotosState.value.takeIf {
            it.userId == userId.id && it.albumUid == album.nodeUid && it.hasLoaded
        }?.let { state ->
            thumbnailQueue.replaceSource(
                userId.id,
                "$ALBUM_PHOTOS_QUEUE_SOURCE:${album.nodeUid}",
                state.photos.filterNot(ProtonGalleryPhoto::hasThumbnail).map(ProtonGalleryPhoto::nodeUid),
            )
        }
    }

    private companion object {
        const val TRASH_SYNC_KEY = "trash"
        const val TIMELINE_QUEUE_SOURCE = "timeline"
        const val ALBUM_COVERS_QUEUE_SOURCE = "album-covers"
        const val TRASH_QUEUE_SOURCE = "trash"
        const val ALBUM_PHOTOS_QUEUE_SOURCE = "album"
    }
}
