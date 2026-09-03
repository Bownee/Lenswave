package com.bownee.lenswave.proton

import android.graphics.Bitmap
import com.bownee.lenswave.gallery.ProtonGalleryReader
import com.bownee.lenswave.gallery.ProtonOriginalMediaSource
import com.bownee.lenswave.gallery.ProtonPhotoMutations
import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import com.bownee.lenswave.gallery.ProtonThumbnailImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.PhotoTagsUpdate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application gateway for Proton Photos capabilities. Focused repositories own synchronization,
 * albums, and downloads; this class enforces the active-session boundary around them.
 *
 * Consumers inject one of the narrow interfaces it implements rather than the class itself;
 * the background thumbnail worker reaches its slice through [thumbnailWork].
 */
@Singleton
class ProtonPhotoGateway
    @Inject
    internal constructor(
        private val timeline: ProtonTimelineRepository,
        private val albums: ProtonAlbumRepository,
        private val downloads: ProtonDownloadRepository,
        private val thumbnailQueue: ProtonThumbnailQueue,
        private val clientProvider: ProtonPhotosClientProvider,
        private val cache: ProtonSessionCache,
        private val sessionGuard: ProtonSessionGuard,
    ) : ProtonGalleryReader,
        ProtonSessionLifecycle,
        ProtonThumbnailImageSource,
        ProtonOriginalMediaSource,
        ProtonPhotoMutations {
        override val state: StateFlow<ProtonGalleryState> = timeline.state
        override val albumsState: StateFlow<ProtonAlbumsState> = albums.albumsState
        override val albumPhotosState: StateFlow<ProtonAlbumPhotosState> = albums.albumPhotosState

        /** Background thumbnail work; kept off the public class surface, see [ProtonThumbnailWorkGateway]. */
        internal val thumbnailWork: ProtonThumbnailWorkGateway = ThumbnailWork()

        override suspend fun activate(userId: UserId) {
            withContext(Dispatchers.IO) {
                sessionGuard.activate(userId) { previousUserId ->
                    previousUserId?.let { previous ->
                        clientProvider.disconnect(previous)
                        cache.clearUser(previous.id)
                        downloads.forgetUser(previous)
                    }
                    timeline.reset()
                    albums.reset()
                    cache.trimUser(userId.id)
                    timeline.loadCached(userId)
                    albums.loadCached(userId)
                    reconcileTimelineThumbnailQueue(userId)
                    reconcileAlbumCoverThumbnailQueue(userId)
                }
            }
        }

        override suspend fun syncTimelineMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) = withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                timeline.syncMetadata(userId, forceRemote)
                reconcileTimelineThumbnailQueue(userId)
                coroutineScope {
                    launch { timeline.syncTagMetadata(userId, ProtonMediaTag.VIDEOS, forceRemote) }
                    launch { timeline.syncTagMetadata(userId, ProtonMediaTag.FAVORITES, forceRemote) }
                }
                Unit
            }
        }

        override suspend fun syncTagMetadata(
            userId: UserId,
            tag: ProtonMediaTag,
            forceRemote: Boolean,
        ) = withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                timeline.syncTagMetadata(userId, tag, forceRemote)
            }
        }

        override suspend fun syncAlbumsMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) = withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                albums.syncMetadata(userId, forceRemote)
                reconcileAlbumCoverThumbnailQueue(userId)
            }
        }

        override suspend fun loadCachedAlbum(
            userId: UserId,
            album: ProtonAlbumReference,
        ) {
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
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) {
                    albums.syncAlbumPhotoMetadata(userId, album, forceRemote)
                    reconcileAlbumThumbnailQueue(userId, album)
                }
            }
        }

        override suspend fun downloadOriginal(
            userId: UserId,
            nodeUid: String,
        ): File =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) { downloads.downloadOriginal(userId, nodeUid) }
            }

        override suspend fun prepareCachedOriginal(
            userId: UserId,
            nodeUid: String,
        ): File? =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) { downloads.prepareCachedOriginal(userId, nodeUid) }
            }

        override suspend fun downloadOriginalProgressively(
            userId: UserId,
            nodeUid: String,
            onReady: suspend (ProtonOriginalStream) -> Unit,
        ): File =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) {
                    downloads.downloadOriginalProgressively(userId, nodeUid, onReady)
                }
            }

        override suspend fun getOriginalFileName(
            userId: UserId,
            nodeUid: String,
        ): String? =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) { downloads.getOriginalFileName(userId, nodeUid) }
            }

        override suspend fun loadThumbnail(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) {
                    downloads.loadThumbnail(userId, nodeUid) ?: run {
                        invalidateThumbnailInActiveSession(userId, nodeUid)
                        null
                    }
                }
            }

        override fun peekThumbnail(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? {
            // Synchronous by design: the in-memory peek must not wait on the session guard, so an
            // inactive account simply reports no cached thumbnail.
            if (!sessionGuard.isActive(userId)) return null
            return downloads.peekThumbnail(userId, nodeUid)
        }

        override suspend fun trashPhotos(
            userId: UserId,
            nodeUids: Collection<String>,
        ): ProtonTrashResult {
            return withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) {
                    val requested = nodeUids.distinct()
                    if (requested.isEmpty()) return@withActiveSession ProtonTrashResult()
                    val results = clientProvider.get(userId).trashNodes(requested.map(::NodeUid)).toList()
                    val successful =
                        results
                            .filterIsInstance<NodeResultPair.Success>()
                            .map { it.nodeUid.value }
                            .toSet()
                    if (successful.isNotEmpty()) {
                        timeline.removePhotos(userId, successful)
                        albums.removePhotos(userId, successful)
                    }
                    ProtonTrashResult(
                        trashedCount = successful.size,
                        failedCount = results.count { it is NodeResultPair.Failure },
                    )
                }
            }
        }

        override suspend fun setFavorite(
            userId: UserId,
            nodeUids: Collection<String>,
            favorite: Boolean,
        ): ProtonFavoriteResult =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) {
                    val requested = nodeUids.distinct()
                    if (requested.isEmpty()) return@withActiveSession ProtonFavoriteResult()
                    timeline.syncTagMetadata(userId, ProtonMediaTag.FAVORITES, forceRemote = false)
                    val updates =
                        requested.map { nodeUid ->
                            PhotoTagsUpdate(
                                nodeUid = NodeUid(nodeUid),
                                tagsToAdd = if (favorite) listOf(ProtonMediaTag.FAVORITES.sdkTag) else emptyList(),
                                tagsToRemove = if (favorite) emptyList() else listOf(ProtonMediaTag.FAVORITES.sdkTag),
                            )
                        }
                    val results = clientProvider.get(userId).updatePhotos(updates).toList()
                    val successful =
                        results
                            .filterIsInstance<NodeResultPair.Success>()
                            .mapTo(mutableSetOf()) { result -> result.nodeUid.value }
                    timeline.setFavorite(userId, successful, favorite)
                    ProtonFavoriteResult(
                        updatedCount = successful.size,
                        failedCount = results.count { it is NodeResultPair.Failure },
                    )
                }
            }

        override suspend fun disconnect(userId: UserId) {
            withContext(Dispatchers.IO) {
                sessionGuard.disconnect(userId) { wasActive ->
                    clientProvider.disconnect(userId)
                    cache.clearUser(userId.id)
                    thumbnailQueue.forget(userId.id)
                    downloads.forgetUser(userId)
                    if (wasActive) {
                        timeline.reset()
                        albums.reset()
                    }
                }
            }
        }

        private inner class ThumbnailWork : ProtonThumbnailWorkGateway {
            override suspend fun downloadNextQueuedThumbnailBatch(
                userId: UserId,
                onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
            ): ProtonThumbnailQueueStep =
                withContext(Dispatchers.IO) {
                    sessionGuard.withActiveSession(userId) {
                        processThumbnailBatch(
                            userId,
                            thumbnailQueue.claimReady(
                                userId.id,
                                ProtonThumbnailDownloadPolicy.BACKGROUND_CLAIM_SIZE,
                            ),
                            onProgress,
                        )
                    }
                }

            override suspend fun thumbnailWorkProgress(userId: UserId): ProtonThumbnailWorkProgress =
                withContext(Dispatchers.IO) {
                    sessionGuard.withActiveSession(userId) { thumbnailWorkProgressInActiveSession(userId) }
                }

            override fun updateThumbnailWorkStatus(status: ProtonThumbnailWorkStatus?) {
                timeline.updateThumbnailWorkStatus(status)
            }
        }

        private suspend fun thumbnailWorkProgressInActiveSession(userId: UserId) =
            ProtonThumbnailWorkProgress(
                stored = downloads.storedThumbnailCount(userId),
                pending = thumbnailQueue.pendingCount(userId.id),
            )

        private suspend fun processThumbnailBatch(
            userId: UserId,
            entries: List<ProtonThumbnailQueueEntry>,
            onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit = {},
        ): ProtonThumbnailQueueStep {
            if (entries.isEmpty()) {
                return ProtonThumbnailQueueStep.Idle(thumbnailQueue.hasPending(userId.id))
            }
            val nodeUids = entries.map(ProtonThumbnailQueueEntry::nodeUid)
            val progressMutex = Mutex()
            return try {
                val result =
                    downloads.downloadThumbnails(userId, nodeUids) { progress ->
                        progressMutex.withLock {
                            settleThumbnailProgress(userId, progress)
                            onProgress(thumbnailWorkProgressInActiveSession(userId))
                        }
                    }
                if (result.failures.isEmpty()) {
                    ProtonThumbnailQueueStep.Downloaded
                } else {
                    ProtonThumbnailQueueStep.Failed
                }
            } catch (error: CancellationException) {
                thumbnailQueue.release(userId.id, nodeUids)
                throw error
            } catch (_: Throwable) {
                thumbnailQueue.settle(userId.id, emptySet(), nodeUids.toSet())
                onProgress(thumbnailWorkProgressInActiveSession(userId))
                ProtonThumbnailQueueStep.Failed
            }
        }

        private suspend fun settleThumbnailProgress(
            userId: UserId,
            result: ThumbnailBatchResult,
        ) {
            val completed =
                thumbnailQueue.settle(
                    userId.id,
                    result.successfulNodeUids,
                    result.failures.keys,
                )
            val completedNodeUids = completed.mapTo(mutableSetOf(), ProtonThumbnailQueueEntry::nodeUid)
            result.successfulNodeUids
                .filterNot(completedNodeUids::contains)
                .forEach { nodeUid -> downloads.removeThumbnail(userId, nodeUid) }
            publishThumbnailAvailability(userId, completed)
        }

        private suspend fun invalidateThumbnailInActiveSession(
            userId: UserId,
            nodeUid: String,
        ) {
            val sources = thumbnailSources(nodeUid)
            downloads.removeThumbnail(userId, nodeUid)
            val nodeUids = setOf(nodeUid)
            timeline.markThumbnailsUnavailable(userId, nodeUids)
            albums.markCoverThumbnailsUnavailable(userId, nodeUids)
            albums.markAlbumPhotoThumbnailsUnavailable(userId, nodeUids)
            thumbnailQueue.retryNow(
                userId.id,
                ProtonThumbnailCandidate(nodeUid, thumbnailCaptureTime(nodeUid)),
                sources,
            )
        }

        private fun publishThumbnailAvailability(
            userId: UserId,
            entries: Collection<ProtonThumbnailQueueEntry>,
        ) {
            if (entries.isEmpty()) return
            val timelineNodeUids = mutableSetOf<String>()
            val albumCoverNodeUids = mutableSetOf<String>()
            val albumPhotoNodeUids = mutableSetOf<String>()
            entries.forEach { entry ->
                if (ProtonSyncKeys.QueueSource.TIMELINE in entry.sources) timelineNodeUids += entry.nodeUid
                if (ProtonSyncKeys.QueueSource.ALBUM_COVERS in entry.sources) albumCoverNodeUids += entry.nodeUid
                if (entry.sources.any(ProtonSyncKeys.QueueSource::isAlbumPhotos)) {
                    albumPhotoNodeUids += entry.nodeUid
                }
            }
            timeline.markThumbnailsAvailable(userId, timelineNodeUids)
            albums.markCoverThumbnailsAvailable(userId, albumCoverNodeUids)
            albums.markAlbumPhotoThumbnailsAvailable(userId, albumPhotoNodeUids)
        }

        private suspend fun reconcileTimelineThumbnailQueue(userId: UserId) {
            timeline.state.value.takeIf { it.userId == userId.id && it.hasLoaded }?.let { state ->
                thumbnailQueue.replaceSource(
                    userId.id,
                    ProtonSyncKeys.QueueSource.TIMELINE,
                    state.photos.filterNot(ProtonGalleryPhoto::hasThumbnail).map { photo ->
                        ProtonThumbnailCandidate(photo.nodeUid, photo.captureTimeEpochSeconds)
                    },
                )
            }
        }

        private suspend fun reconcileAlbumCoverThumbnailQueue(userId: UserId) {
            val albumState = albums.albumsState.value.takeIf { it.userId == userId.id && it.hasLoaded }
            albumState?.let { state ->
                thumbnailQueue.replaceSources(
                    userId.id,
                    mapOf(
                        ProtonSyncKeys.QueueSource.ALBUM_COVERS to
                            state.albums
                                .filterNot(ProtonAlbum::hasCoverThumbnail)
                                .mapNotNull { album ->
                                    album.coverPhotoNodeUid?.let { nodeUid ->
                                        ProtonThumbnailCandidate(nodeUid, UNKNOWN_CAPTURE_TIME)
                                    }
                                },
                    ),
                    state.albums.map(ProtonAlbum::nodeUid),
                )
            }
        }

        private suspend fun reconcileAlbumThumbnailQueue(
            userId: UserId,
            album: ProtonAlbumReference,
        ) {
            albums.albumPhotosState.value
                .takeIf {
                    it.userId == userId.id && it.albumUid == album.nodeUid && it.hasLoaded
                }?.let { state ->
                    thumbnailQueue.replaceSource(
                        userId.id,
                        ProtonSyncKeys.QueueSource.albumPhotos(album.nodeUid),
                        state.photos.filterNot(ProtonGalleryPhoto::hasThumbnail).map { photo ->
                            ProtonThumbnailCandidate(photo.nodeUid, photo.captureTimeEpochSeconds)
                        },
                    )
                }
        }

        private fun thumbnailCaptureTime(nodeUid: String): Long =
            listOfNotNull(
                timeline.state.value.photos
                    .firstOrNull { photo -> photo.nodeUid == nodeUid }
                    ?.captureTimeEpochSeconds,
                albums.albumPhotosState.value.photos
                    .firstOrNull { photo -> photo.nodeUid == nodeUid }
                    ?.captureTimeEpochSeconds,
            ).maxOrNull() ?: UNKNOWN_CAPTURE_TIME

        private fun thumbnailSources(nodeUid: String): Set<String> =
            buildSet {
                if (timeline.state.value.photos
                        .any { photo -> photo.nodeUid == nodeUid }
                ) {
                    add(ProtonSyncKeys.QueueSource.TIMELINE)
                }
                if (albums.albumsState.value.albums
                        .any { album -> album.coverPhotoNodeUid == nodeUid }
                ) {
                    add(ProtonSyncKeys.QueueSource.ALBUM_COVERS)
                }
                albums.albumPhotosState.value.let { state ->
                    if (state.photos.any { photo -> photo.nodeUid == nodeUid }) {
                        state.albumUid?.let { albumUid -> add(ProtonSyncKeys.QueueSource.albumPhotos(albumUid)) }
                    }
                }
            }

        private companion object {
            const val UNKNOWN_CAPTURE_TIME = Long.MIN_VALUE
        }
    }
