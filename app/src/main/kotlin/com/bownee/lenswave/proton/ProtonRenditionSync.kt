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
import java.util.concurrent.atomic.AtomicInteger
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
        /** The worker serves one user at a time, so one counter across users is enough. */
        private val batchesSinceForcedFlush = AtomicInteger()

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
                        processThumbnailBatch(userId, batch.entries, onProgress).also { flushAfterBatch(userId) }
                    }

                    ProtonQueueName.PREVIEWS -> {
                        processPreviewBatch(userId, batch.entries, onProgress).also { flushAfterBatch(userId) }
                    }

                    null -> {
                        idle(userId, allowPreviews)
                    }
                }
            } catch (error: CancellationException) {
                // A stopped worker or the run deadline: the settles coalesced in memory would be
                // lost with the process, so they reach disk now.
                withContext(NonCancellable) { flushQueues(userId) }
                throw error
            }
        }

        suspend fun flushQueues(userId: UserId) {
            batchesSinceForcedFlush.set(0)
            thumbnailQueue.flush(userId.id)
            previewQueue.flush(userId.id)
        }

        /**
         * Settles are left to the queues' own debounce, which writes once per few seconds
         * however many batches ran; forcing a write after every batch rewrote the whole queue
         * over a thousand times across a large backfill. A forced write every few batches only
         * bounds what a killed process downloads again.
         */
        private suspend fun flushAfterBatch(userId: UserId) {
            if (ProtonQueueFlushPolicy.shouldFlushAfterBatch(batchesSinceForcedFlush.incrementAndGet())) {
                flushQueues(userId)
            }
        }

        /** Nothing is claimable; the run may end here, so whatever is unflushed is written. */
        private suspend fun idle(
            userId: UserId,
            allowPreviews: Boolean,
        ): ProtonThumbnailQueueStep.Idle {
            val idle =
                ProtonBackgroundBatchPolicy.idle(
                    thumbnailsPending = thumbnailQueue.hasPending(userId.id),
                    previewsPending = previewQueue.hasPending(userId.id),
                    thumbnailRetryDelayMillis = thumbnailQueue.retryDelayMillis(userId.id),
                    previewRetryDelayMillis = previewQueue.retryDelayMillis(userId.id),
                    allowPreviews = allowPreviews,
                )
            if (ProtonBackgroundBatchPolicy.hasStaleClaims(idle)) {
                // This is the only claimer, so a ready entry nobody could claim is a claim some
                // earlier batch never gave back. Clearing them lets the next step process it
                // instead of the worker polling every second until the run deadline.
                thumbnailQueue.releaseAll(userId.id)
                previewQueue.releaseAll(userId.id)
            }
            flushQueues(userId)
            return idle
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
                // The release takes the queue mutex; from a cancelled coroutine that suspension
                // would throw instead and leave the claims behind for the rest of the process.
                withContext(NonCancellable) { thumbnailQueue.release(userId.id, nodeUids) }
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
            val settledFailures = mutableSetOf<String>()
            try {
                val result =
                    source.downloadPreviews(userId, nodeUids) { progress ->
                        progressMutex.withLock {
                            settlePreviews(userId, progress, marks, settledFailures)
                            publishMarks(userId, marks, force = false)
                            onProgress(progress(userId))
                        }
                    }
                // Settling the final result as well guarantees no claimed entry is left behind,
                // which would otherwise keep the run spinning on a queue it can never drain. A
                // failure already settled from a progress report is not settled again: every
                // settle is a backoff step, and a pass is one failure.
                progressMutex.withLock { settlePreviews(userId, result, marks, settledFailures) }
            } catch (error: CancellationException) {
                withContext(NonCancellable) { previewQueue.release(userId.id, nodeUids) }
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
            settledFailures: MutableSet<String>,
        ) {
            val failures = result.failures.filterKeys { nodeUid -> settledFailures.add(nodeUid) }
            previewQueue.settle(userId.id, result.successfulNodeUids, failures)
            marks.previews += result.successfulNodeUids
        }

        private suspend fun settleThumbnails(
            userId: UserId,
            result: ThumbnailBatchResult,
            marks: PendingMarks,
        ) {
            val completed = thumbnailQueue.settle(userId.id, result.successfulNodeUids, result.failures)
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
