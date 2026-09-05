package com.bownee.lenswave.proton

import android.graphics.Bitmap
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.gallery.ProtonGalleryReader
import com.bownee.lenswave.gallery.ProtonOriginalMediaSource
import com.bownee.lenswave.gallery.ProtonPhotoMutations
import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import com.bownee.lenswave.gallery.ProtonThumbnailImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
class ProtonPhotoGateway internal constructor(
    private val timeline: ProtonTimelineRepository,
    private val albums: ProtonAlbumRepository,
    private val originals: ProtonOriginalDownloads,
    private val renditions: ProtonRenditionDownloads,
    private val renditionSync: ProtonRenditionSync,
    private val thumbnailQueue: ProtonThumbnailQueue,
    private val previewQueue: ProtonThumbnailQueue,
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonSessionCache,
    private val sessionGuard: ProtonSessionGuard,
    /** Process-wide, like the session itself; [runActivationHousekeeping] guards every use. */
    private val housekeepingScope: CoroutineScope,
) : ProtonGalleryReader,
    ProtonSessionLifecycle,
    ProtonThumbnailImageSource,
    ProtonOriginalMediaSource,
    ProtonPhotoMutations {
    @Inject
    internal constructor(
        timeline: ProtonTimelineRepository,
        albums: ProtonAlbumRepository,
        originals: ProtonOriginalDownloads,
        renditions: ProtonRenditionDownloads,
        renditionSync: ProtonRenditionSync,
        @ThumbnailQueue thumbnailQueue: ProtonThumbnailQueue,
        @PreviewQueue previewQueue: ProtonThumbnailQueue,
        clientProvider: ProtonPhotosClientProvider,
        cache: ProtonSessionCache,
        sessionGuard: ProtonSessionGuard,
    ) : this(
        timeline = timeline,
        albums = albums,
        originals = originals,
        renditions = renditions,
        renditionSync = renditionSync,
        thumbnailQueue = thumbnailQueue,
        previewQueue = previewQueue,
        clientProvider = clientProvider,
        cache = cache,
        sessionGuard = sessionGuard,
        housekeepingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    override val state: StateFlow<ProtonGalleryState> = timeline.state
    override val albumsState: StateFlow<ProtonAlbumsState> = albums.albumsState
    override val albumPhotosState: StateFlow<ProtonAlbumPhotosState> = albums.albumPhotosState

    /**
     * The one housekeeping run in flight, under [housekeepingMutex]. Each activation that
     * actually transitioned replaces it, so retried transitions and account switches never
     * pile up concurrent runs over the same queues.
     */
    private val housekeepingMutex = Mutex()
    private var housekeepingJob: Job? = null

    /** Background thumbnail work; kept off the public class surface, see [ProtonThumbnailWorkGateway]. */
    internal val thumbnailWork: ProtonThumbnailWorkGateway = ThumbnailWork()

    /**
     * The transition holds only what the grid needs: the cached listings, loaded so the grid
     * gets them as early as possible. Everything else runs afterwards as an ordinary session
     * operation, so thumbnail reads and the account state stop waiting on cache trimming and
     * queue reconciliation, and a disconnect still waits for that housekeeping to finish before
     * it erases the user's files. Wiping the previous process's plaintext copies is not part
     * of the transition either (thousands of unlinks on a large cache held every thumbnail load
     * back); the original store wipes them, once per process, before it materializes or reads
     * a plaintext copy.
     */
    override suspend fun activate(userId: UserId) {
        val transitioned =
            withContext(Dispatchers.IO) {
                sessionGuard.activate(userId) { previousUserId ->
                    previousUserId?.let { previous ->
                        clientProvider.disconnect(previous)
                        // As in disconnect: the queues drop their pending writes first, or a
                        // debounced flush could land after the clear and recreate the previous
                        // user's directory and data key.
                        thumbnailQueue.forget(previous.id)
                        previewQueue.forget(previous.id)
                        originals.forgetUser(previous)
                        cache.clearUser(previous.id)
                    }
                    timeline.reset()
                    albums.reset()
                    timeline.loadCached(userId)
                    albums.loadCached(userId)
                }
            }
        // An activation the guard skipped (the account is already active) changes nothing
        // the housekeeping would act on; only a transition earns a fresh run.
        if (transitioned) launchActivationHousekeeping(userId)
    }

    private suspend fun launchActivationHousekeeping(userId: UserId) {
        housekeepingMutex.withLock {
            housekeepingJob?.cancelAndJoin()
            housekeepingJob = housekeepingScope.launch { runActivationHousekeeping(userId) }
        }
    }

    private suspend fun runActivationHousekeeping(userId: UserId) {
        try {
            sessionGuard.withActiveSession(userId) {
                cache.trimUser(userId.id)
                reconcileTimelineThumbnailQueue(userId)
                reconcileTimelinePreviewQueue(userId)
                reconcileAlbumCoverThumbnailQueue(userId)
            }
        } catch (_: ProtonSessionChangedException) {
            // The account changed underneath; the next activation does its own housekeeping.
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.SESSION_HOUSEKEEPING, error)
        }
    }

    override suspend fun syncTimelineMetadata(
        userId: UserId,
        forceRemote: Boolean,
    ) = withContext(Dispatchers.IO) {
        sessionGuard.withActiveSession(userId) {
            timeline.syncMetadata(userId, forceRemote)
            reconcileTimelineThumbnailQueue(userId)
            reconcileTimelinePreviewQueue(userId)
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
            sessionGuard.withActiveSession(userId) { originals.downloadOriginal(userId, nodeUid) }
        }

    override suspend fun prepareCachedOriginal(
        userId: UserId,
        nodeUid: String,
    ): File? =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            sessionGuard.withActiveSession(userId) {
                // A prefetch the viewer abandoned stops between decrypt chunks with a
                // cancellation, so the photo on screen gets the disk and the CPU back.
                originals.prepareCachedOriginal(userId, nodeUid) { job?.isActive != false }
            }
        }

    override suspend fun downloadOriginalProgressively(
        userId: UserId,
        nodeUid: String,
        onReady: suspend (ProtonOriginalStream) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                originals.downloadOriginalProgressively(userId, nodeUid, onReady)
            }
        }

    override suspend fun getOriginalFileName(
        userId: UserId,
        nodeUid: String,
    ): String? =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { originals.getOriginalFileName(userId, nodeUid) }
        }

    override suspend fun loadPreview(
        userId: UserId,
        nodeUid: String,
        targetLongEdge: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { renditions.loadPreview(userId, nodeUid, targetLongEdge) }
        }

    override suspend fun loadThumbnail(
        userId: UserId,
        nodeUid: String,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            sessionGuard.withActiveSession(userId) {
                // A load the grid cancelled mid-flight throws instead of returning null, so
                // it is never mistaken for a corrupt thumbnail below.
                try {
                    renditions.loadThumbnail(userId, nodeUid) { job?.isActive != false } ?: run {
                        invalidateThumbnailInActiveSession(userId, nodeUid)
                        null
                    }
                } catch (_: ProtonRenditionUnavailableException) {
                    // The file is intact but cannot be decrypted right now; the cell keeps its
                    // placeholder and asks again on its next bind. Never invalidate or re-queue.
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
        return renditions.peekThumbnail(userId, nodeUid)
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
                    // Listings before renditions: the timeline removal deletes the files, so the
                    // album indexes must have dropped the photo first.
                    albums.removePhotos(userId, successful)
                    timeline.removePhotos(userId, successful)
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
                // The queues drop their pending writes and the originals stop their
                // transfers first so none can land after the user's directory is gone.
                thumbnailQueue.forget(userId.id)
                previewQueue.forget(userId.id)
                originals.forgetUser(userId)
                cache.clearUser(userId.id)
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
            allowPreviews: Boolean,
            onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
        ): ProtonThumbnailQueueStep =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) {
                    renditionSync.downloadNextBatch(userId, allowPreviews, onProgress)
                }
            }

        override suspend fun thumbnailWorkProgress(userId: UserId): ProtonThumbnailWorkProgress =
            withContext(Dispatchers.IO) {
                sessionGuard.withActiveSession(userId) { renditionSync.progress(userId) }
            }
    }

    /**
     * A stored thumbnail that no longer decodes is dropped, hidden from every listing, and
     * queued again at the front. The grid asks about one cell at a time while it scrolls, so
     * the listing lookups are memoized per state snapshot (and survive the new snapshot the
     * marking below publishes, see [ProtonNodeUidIndex]) and the queue write is left to the
     * queue's own debounce rather than persisted per item.
     */
    private suspend fun invalidateThumbnailInActiveSession(
        userId: UserId,
        nodeUid: String,
    ) {
        val timelinePhoto = timelinePhotoIndex.find(timeline.state.value.photos, nodeUid)
        val albumPhotosState = albums.albumPhotosState.value
        val albumPhoto = albumPhotoIndex.find(albumPhotosState.photos, nodeUid)
        val isAlbumCover = albumCoverIndex.contains(albums.albumsState.value.albums, nodeUid)
        val sources =
            buildSet {
                if (timelinePhoto != null) add(ProtonSyncKeys.QueueSource.TIMELINE)
                if (isAlbumCover) add(ProtonSyncKeys.QueueSource.ALBUM_COVERS)
                if (albumPhoto != null) {
                    albumPhotosState.albumUid?.let { albumUid ->
                        add(ProtonSyncKeys.QueueSource.albumPhotos(albumUid))
                    }
                }
            }
        val captureTime =
            listOfNotNull(timelinePhoto, albumPhoto)
                .maxOfOrNull(ProtonGalleryPhoto::captureTimeEpochSeconds)
                ?: UNKNOWN_CAPTURE_TIME
        renditions.removeThumbnail(userId, nodeUid)
        val nodeUids = setOf(nodeUid)
        timeline.markThumbnailsUnavailable(userId, nodeUids)
        albums.markCoverThumbnailsUnavailable(userId, nodeUids)
        albums.markAlbumPhotoThumbnailsUnavailable(userId, nodeUids)
        thumbnailQueue.retryNow(userId.id, ProtonThumbnailCandidate(nodeUid, captureTime), sources)
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

    /**
     * Every timeline photo without a stored preview is queued, newest first. Known videos are
     * skipped: the viewer never shows a preview for them.
     */
    private suspend fun reconcileTimelinePreviewQueue(userId: UserId) {
        timeline.state.value.takeIf { it.userId == userId.id && it.hasLoaded }?.let { state ->
            val videoNodeUids =
                state.tags[ProtonMediaTag.VIDEOS]
                    ?.photos
                    ?.mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)
                    .orEmpty()
            previewQueue.replaceSource(
                userId.id,
                ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS,
                state.photos
                    .filterNot { photo -> photo.hasPreview || photo.nodeUid in videoNodeUids }
                    .map { photo -> ProtonThumbnailCandidate(photo.nodeUid, photo.captureTimeEpochSeconds) },
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

    private val timelinePhotoIndex = ProtonNodeUidIndex(ProtonGalleryPhoto::nodeUid)
    private val albumPhotoIndex = ProtonNodeUidIndex(ProtonGalleryPhoto::nodeUid)
    private val albumCoverIndex = ProtonNodeUidIndex(ProtonAlbum::coverPhotoNodeUid)

    private companion object {
        const val UNKNOWN_CAPTURE_TIME = Long.MIN_VALUE
    }
}
