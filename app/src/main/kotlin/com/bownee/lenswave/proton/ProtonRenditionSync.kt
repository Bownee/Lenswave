package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/** What the background sync needs from the rendition downloader; [ProtonRenditionDownloads] in production. */
internal interface ProtonRenditionSource {
    suspend fun downloadThumbnails(
        userId: UserId,
        nodeUids: Collection<String>,
        onProgress: suspend (ThumbnailBatchResult) -> Unit,
    ): ThumbnailBatchResult

    suspend fun downloadPreviews(
        userId: UserId,
        nodeUids: Collection<String>,
        onProgress: suspend (ThumbnailBatchResult) -> Unit,
    ): ThumbnailBatchResult

    fun removeThumbnail(
        userId: UserId,
        nodeUid: String,
    )

    fun storedThumbnailCount(userId: UserId): Int

    fun storedPreviewCount(userId: UserId): Int
}

/** Where newly stored renditions are announced so the gallery and albums can show them. */
internal interface ProtonRenditionAvailability {
    fun thumbnailsAvailable(
        userId: UserId,
        timelineNodeUids: Set<String>,
        albumCoverNodeUids: Set<String>,
        albumPhotoNodeUids: Set<String>,
    )

    fun previewsAvailable(
        userId: UserId,
        nodeUids: Set<String>,
    )
}

/**
 * Serves the background worker one batch at a time: claims from the thumbnail queue first and the
 * preview queue second, downloads, and settles every result back into the queue that owns it.
 * Session guarding is the gateway's job, so this class can be exercised with fakes.
 */
@Singleton
internal class ProtonRenditionSync
    @Inject
    constructor(
        private val source: ProtonRenditionSource,
        private val availability: ProtonRenditionAvailability,
        @ThumbnailQueue private val thumbnailQueue: ProtonThumbnailQueue,
        @PreviewQueue private val previewQueue: ProtonThumbnailQueue,
        private val clock: LenswaveClock,
    ) {
        suspend fun downloadNextBatch(
            userId: UserId,
            allowPreviews: Boolean,
            onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
        ): ProtonThumbnailQueueStep {
            val batch =
                ProtonBackgroundBatchPolicy.choose(
                    thumbnailBatch =
                        thumbnailQueue.claimReady(userId.id, ProtonThumbnailDownloadPolicy.BACKGROUND_CLAIM_SIZE),
                    allowPreviews = allowPreviews,
                    claimPreviews = {
                        previewQueue.claimReady(userId.id, ProtonThumbnailDownloadPolicy.BACKGROUND_CLAIM_SIZE)
                    },
                )
            try {
                return when (batch?.queue) {
                    ProtonQueueName.THUMBNAILS -> {
                        processThumbnailBatch(userId, batch.entries, onProgress)
                    }

                    ProtonQueueName.PREVIEWS -> {
                        processPreviewBatch(userId, batch.entries, onProgress)
                    }

                    null -> {
                        ProtonBackgroundBatchPolicy.idle(
                            thumbnailsPending = thumbnailQueue.hasPending(userId.id),
                            previewsPending = previewQueue.hasPending(userId.id),
                            thumbnailRetryDelayMillis = thumbnailQueue.retryDelayMillis(userId.id),
                            previewRetryDelayMillis = previewQueue.retryDelayMillis(userId.id),
                            allowPreviews = allowPreviews,
                        )
                    }
                }
            } finally {
                // Settles are coalesced in memory while a batch runs; the end of every step, a
                // stopped worker and the run deadline included, is where they must reach disk.
                withContext(NonCancellable) { flushQueues(userId) }
            }
        }

        suspend fun flushQueues(userId: UserId) {
            thumbnailQueue.flush(userId.id)
            previewQueue.flush(userId.id)
        }

        suspend fun progress(userId: UserId): ProtonThumbnailWorkProgress =
            ProtonThumbnailWorkProgress(
                stored = source.storedThumbnailCount(userId),
                pending = thumbnailQueue.pendingCount(userId.id),
                previewsStored = source.storedPreviewCount(userId),
                previewsPending = previewQueue.pendingCount(userId.id),
            )

        private suspend fun processThumbnailBatch(
            userId: UserId,
            entries: List<ProtonThumbnailQueueEntry>,
            onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
        ): ProtonThumbnailQueueStep {
            val nodeUids = entries.map(ProtonThumbnailQueueEntry::nodeUid)
            val progressMutex = Mutex()
            val marks = PendingMarks()
            try {
                source.downloadThumbnails(userId, nodeUids) { result ->
                    progressMutex.withLock {
                        settleThumbnails(userId, result, marks)
                        publishMarks(userId, marks, force = false)
                        onProgress(progress(userId))
                    }
                }
            } catch (error: CancellationException) {
                thumbnailQueue.release(userId.id, nodeUids)
                throw error
            } catch (_: Throwable) {
                thumbnailQueue.settle(userId.id, emptySet(), nodeUids.toSet())
                onProgress(progress(userId))
            } finally {
                // Whatever was stored is shown, however the batch ended.
                publishMarks(userId, marks, force = true)
            }
            return ProtonThumbnailQueueStep.Processed
        }

        private suspend fun processPreviewBatch(
            userId: UserId,
            entries: List<ProtonThumbnailQueueEntry>,
            onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
        ): ProtonThumbnailQueueStep {
            val nodeUids = entries.map(ProtonThumbnailQueueEntry::nodeUid)
            val progressMutex = Mutex()
            val marks = PendingMarks()
            try {
                val result =
                    source.downloadPreviews(userId, nodeUids) { progress ->
                        progressMutex.withLock {
                            settlePreviews(userId, progress, marks)
                            publishMarks(userId, marks, force = false)
                            onProgress(progress(userId))
                        }
                    }
                // Settling the final result as well guarantees no claimed entry is left behind,
                // which would otherwise keep the run spinning on a queue it can never drain.
                progressMutex.withLock { settlePreviews(userId, result, marks) }
            } catch (error: CancellationException) {
                previewQueue.release(userId.id, nodeUids)
                throw error
            } catch (_: Throwable) {
                previewQueue.settle(userId.id, emptySet(), nodeUids.toSet())
                onProgress(progress(userId))
            } finally {
                publishMarks(userId, marks, force = true)
            }
            return ProtonThumbnailQueueStep.Processed
        }

        private suspend fun settlePreviews(
            userId: UserId,
            result: ThumbnailBatchResult,
            marks: PendingMarks,
        ) {
            previewQueue.settle(userId.id, result.successfulNodeUids, result.failures.keys)
            marks.previews += result.successfulNodeUids
        }

        private suspend fun settleThumbnails(
            userId: UserId,
            result: ThumbnailBatchResult,
            marks: PendingMarks,
        ) {
            val completed = thumbnailQueue.settle(userId.id, result.successfulNodeUids, result.failures.keys)
            val completedNodeUids = completed.mapTo(mutableSetOf(), ProtonThumbnailQueueEntry::nodeUid)
            // A thumbnail nothing asked for any more (its photo left every listing meanwhile)
            // would only take up space.
            result.successfulNodeUids
                .filterNot(completedNodeUids::contains)
                .forEach { nodeUid -> source.removeThumbnail(userId, nodeUid) }
            completed.forEach { entry ->
                if (ProtonSyncKeys.QueueSource.TIMELINE in entry.sources) marks.timeline += entry.nodeUid
                if (ProtonSyncKeys.QueueSource.ALBUM_COVERS in entry.sources) marks.albumCovers += entry.nodeUid
                if (entry.sources.any(ProtonSyncKeys.QueueSource::isAlbumPhotos)) marks.albumPhotos += entry.nodeUid
            }
            // A preview fetched in place of a missing thumbnail is a preview already; the preview
            // queue must not download it a second time.
            if (result.previewsStored.isNotEmpty()) {
                previewQueue.settle(userId.id, result.previewsStored, emptySet())
                marks.previews += result.previewsStored
            }
        }

        /**
         * Marking renditions available copies every listing the gallery observes, so the marks
         * gathered over a batch are published at most every
         * [ProtonThumbnailWorkPolicy.PROGRESS_PUBLISH_INTERVAL_MILLIS], and once more when the
         * batch ends so nothing stored waits for the next one.
         */
        private fun publishMarks(
            userId: UserId,
            marks: PendingMarks,
            force: Boolean,
        ) {
            if (marks.isEmpty()) return
            val now = clock.nowMillis()
            if (!ProtonThumbnailWorkPolicy.shouldPublishProgress(marks.lastPublishedMillis, now, force)) return
            if (marks.timeline.isNotEmpty() || marks.albumCovers.isNotEmpty() || marks.albumPhotos.isNotEmpty()) {
                availability.thumbnailsAvailable(
                    userId,
                    marks.timeline.toSet(),
                    marks.albumCovers.toSet(),
                    marks.albumPhotos.toSet(),
                )
            }
            if (marks.previews.isNotEmpty()) availability.previewsAvailable(userId, marks.previews.toSet())
            marks.clear(now)
        }

        /** Renditions stored since the last publication, and when that was. */
        private class PendingMarks {
            val timeline = mutableSetOf<String>()
            val albumCovers = mutableSetOf<String>()
            val albumPhotos = mutableSetOf<String>()
            val previews = mutableSetOf<String>()

            /** Null until the first publication so the first stored rendition shows at once. */
            var lastPublishedMillis: Long? = null

            fun isEmpty(): Boolean =
                timeline.isEmpty() && albumCovers.isEmpty() && albumPhotos.isEmpty() && previews.isEmpty()

            fun clear(publishedAtMillis: Long) {
                timeline.clear()
                albumCovers.clear()
                albumPhotos.clear()
                previews.clear()
                lastPublishedMillis = publishedAtMillis
            }
        }
    }

/** Publishes stored renditions into the timeline and album state the gallery observes. */
internal class ProtonRenditionAvailabilityPublisher
    @Inject
    constructor(
        private val timeline: ProtonTimelineRepository,
        private val albums: ProtonAlbumRepository,
    ) : ProtonRenditionAvailability {
        override fun thumbnailsAvailable(
            userId: UserId,
            timelineNodeUids: Set<String>,
            albumCoverNodeUids: Set<String>,
            albumPhotoNodeUids: Set<String>,
        ) {
            timeline.markThumbnailsAvailable(userId, timelineNodeUids)
            albums.markCoverThumbnailsAvailable(userId, albumCoverNodeUids)
            albums.markAlbumPhotoThumbnailsAvailable(userId, albumPhotoNodeUids)
        }

        override fun previewsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            timeline.markPreviewsAvailable(userId, nodeUids)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonRenditionSyncModule {
    @Binds abstract fun bindRenditionSource(implementation: ProtonRenditionDownloads): ProtonRenditionSource

    @Binds abstract fun bindRenditionAvailability(
        implementation: ProtonRenditionAvailabilityPublisher,
    ): ProtonRenditionAvailability
}
