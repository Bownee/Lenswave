package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * Marking renditions available copies every listing the gallery observes, so it runs on
 * [publishScope], a coroutine of the sync's own, never on the download coroutine: it used to be
 * called from the progress callback while the downloader was still receiving from the SDK
 * channel against a six-second idle timeout, and a slow publication read as a quiet SDK.
 */
@Singleton
internal class ProtonRenditionSync(
    private val source: ProtonRenditionSource,
    private val availability: ProtonRenditionAvailability,
    private val thumbnailQueue: ProtonThumbnailQueue,
    private val previewQueue: ProtonThumbnailQueue,
    private val clock: LenswaveClock,
    private val publishScope: CoroutineScope,
) {
    @Inject
    constructor(
        source: ProtonRenditionSource,
        availability: ProtonRenditionAvailability,
        @ThumbnailQueue thumbnailQueue: ProtonThumbnailQueue,
        @PreviewQueue previewQueue: ProtonThumbnailQueue,
        clock: LenswaveClock,
    ) : this(
        source,
        availability,
        thumbnailQueue,
        previewQueue,
        clock,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** The worker serves one user at a time, so one counter across users is enough. */
    private val batchesSinceForcedFlush = AtomicInteger()

    /** One [MarkPublisher] per user for the run in progress; see [finishPublishing]. */
    private val publishers = mutableMapOf<String, MarkPublisher>()
    private val publishersMutex = Mutex()

    private suspend fun publisher(userId: UserId): MarkPublisher =
        publishersMutex.withLock { publishers.getOrPut(userId.id) { MarkPublisher(userId) } }

    suspend fun downloadNextBatch(
        userId: UserId,
        allowPreviews: Boolean,
        onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
    ): ProtonThumbnailQueueStep {
        val batch =
            ProtonBackgroundBatchPolicy.choose(
                thumbnailBatch =
                    thumbnailQueue.claimReady(
                        userId.id,
                        ProtonThumbnailDownloadPolicy.BACKGROUND_CLAIM_SIZE,
                        previewsAllowed = allowPreviews,
                    ),
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
            // lost with the process, so they reach disk now, and whatever was stored is shown.
            withContext(NonCancellable) {
                flushQueues(userId)
                finishPublishing(userId)
            }
            throw error
        }
    }

    /**
     * Publishes whatever the user's batches have stored and not yet published, and returns once
     * it has. Called when a run goes idle and when it is stopped; the gateway calls it as well
     * when the user's account is disconnected or switched away from, so no publication lands on
     * listings that are being reset. The next batch starts a publisher of its own.
     */
    suspend fun finishPublishing(userId: UserId) {
        val publisher = publishersMutex.withLock { publishers.remove(userId.id) } ?: return
        publisher.finish()
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
                thumbnailsPending = thumbnailQueue.hasPending(userId.id, previewsAllowed = allowPreviews),
                previewsPending = previewQueue.hasPending(userId.id),
                thumbnailRetryDelayMillis = thumbnailQueue.retryDelayMillis(userId.id, previewsAllowed = allowPreviews),
                previewRetryDelayMillis = previewQueue.retryDelayMillis(userId.id),
                allowPreviews = allowPreviews,
                thumbnailsAwaitingPreviews = thumbnailQueue.hasEntriesAwaitingPreviews(userId.id),
            )
        if (ProtonBackgroundBatchPolicy.hasStaleClaims(idle)) {
            // This is the only claimer, so a ready entry nobody could claim is a claim some
            // earlier batch never gave back. Clearing them lets the next step process it
            // instead of the worker polling every second until the run deadline.
            thumbnailQueue.releaseAll(userId.id)
            previewQueue.releaseAll(userId.id)
        }
        flushQueues(userId)
        finishPublishing(userId)
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
        val marks = publisher(userId)
        val settled = SettledNodes()
        try {
            val result =
                source.downloadThumbnails(userId, nodeUids) { progress ->
                    progressMutex.withLock {
                        settleThumbnails(userId, progress, marks, settled)
                        onProgress(progress(userId))
                    }
                }
            // The final result is settled as well, as for previews: it is the only place the
            // nodes deferred to a preview-fetching run are reported, and a claimed entry left
            // behind would keep the run claiming and releasing it until the deadline.
            progressMutex.withLock { settleThumbnails(userId, result, marks, settled) }
        } catch (error: CancellationException) {
            // The release takes the queue mutex; from a cancelled coroutine that suspension
            // would throw instead and leave the claims behind for the rest of the process.
            withContext(NonCancellable) { thumbnailQueue.release(userId.id, nodeUids) }
            throw error
        } catch (error: Throwable) {
            // A connection lost under the batch is not the nodes' fault; see the classifier.
            thumbnailQueue.settle(userId.id, emptySet(), nodeUids.associateWith { classifyBatchFailure(error) })
            onProgress(progress(userId))
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
        val marks = publisher(userId)
        val settledFailures = mutableSetOf<String>()
        try {
            val result =
                source.downloadPreviews(userId, nodeUids) { progress ->
                    progressMutex.withLock {
                        settlePreviews(userId, progress, marks, settledFailures)
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
        } catch (error: Throwable) {
            previewQueue.settle(userId.id, emptySet(), nodeUids.associateWith { classifyBatchFailure(error) })
            onProgress(progress(userId))
        }
        return ProtonThumbnailQueueStep.Processed
    }

    /**
     * What a batch that threw as a whole means for each of its nodes. A missing rendition is a
     * per-node answer and never the whole batch's, so it is not taken from here: dropping every
     * node of a batch on one exception would be wrong however it was worded.
     */
    private fun classifyBatchFailure(error: Throwable): ThumbnailFailureKind =
        ThumbnailFailureClassifier.classify(error).takeIf { kind -> kind == ThumbnailFailureKind.TRANSIENT_NETWORK }
            ?: ThumbnailFailureKind.OTHER

    private suspend fun settlePreviews(
        userId: UserId,
        result: ThumbnailBatchResult,
        marks: MarkPublisher,
        settledFailures: MutableSet<String>,
    ) {
        val failures = result.failures.filterKeys { nodeUid -> settledFailures.add(nodeUid) }
        previewQueue.settle(userId.id, result.successfulNodeUids, failures)
        if (result.successfulNodeUids.isNotEmpty()) marks.add { previews += result.successfulNodeUids }
    }

    /** What earlier reports of one thumbnail batch already settled; see [settleThumbnails]. */
    private class SettledNodes {
        val successes = mutableSetOf<String>()
        val failures = mutableSetOf<String>()
        val previews = mutableSetOf<String>()
    }

    /**
     * [settled] holds the nodes earlier reports of the same batch already settled: the SDK
     * sometimes answers a node twice, and the second answer must not read as "nobody asked
     * for this"; and the final result repeats the failures the progress reports carried, which
     * must not take a second backoff step. The deferred nodes are parked in the queue without
     * a step ([ThumbnailFailureKind.PREVIEW_DEFERRED]).
     */
    private suspend fun settleThumbnails(
        userId: UserId,
        result: ThumbnailBatchResult,
        marks: MarkPublisher,
        settled: SettledNodes,
    ) {
        val failures =
            result.failures.filterKeys { nodeUid -> settled.failures.add(nodeUid) } +
                result.deferredNodeUids
                    .filterNot { nodeUid -> nodeUid in result.successfulNodeUids }
                    .associateWith { ThumbnailFailureKind.PREVIEW_DEFERRED }
        val completed = thumbnailQueue.settle(userId.id, result.successfulNodeUids, failures)
        val completedNodeUids = completed.mapTo(mutableSetOf(), ProtonThumbnailQueueEntry::nodeUid)
        // A thumbnail nothing asked for (never queued, or its photo left every listing while
        // the batch ran and the queue dropped the entry) would only take up space. One this
        // batch already settled is wanted; it is simply reported again.
        result.successfulNodeUids
            .filterNot { nodeUid -> nodeUid in completedNodeUids || nodeUid in settled.successes }
            .forEach { nodeUid -> source.removeThumbnail(userId, nodeUid) }
        settled.successes += result.successfulNodeUids
        // A preview fetched in place of a missing thumbnail is a preview already; the preview
        // queue must not download it a second time.
        val previewsStored = result.previewsStored.filter { nodeUid -> settled.previews.add(nodeUid) }.toSet()
        if (previewsStored.isNotEmpty()) previewQueue.settle(userId.id, previewsStored, emptySet())
        if (completed.isEmpty() && previewsStored.isEmpty()) return
        marks.add {
            completed.forEach { entry ->
                if (ProtonSyncKeys.QueueSource.TIMELINE in entry.sources) timeline += entry.nodeUid
                if (ProtonSyncKeys.QueueSource.ALBUM_COVERS in entry.sources) albumCovers += entry.nodeUid
                if (entry.sources.any(ProtonSyncKeys.QueueSource::isAlbumPhotos)) albumPhotos += entry.nodeUid
            }
            previews += previewsStored
        }
    }

    /**
     * Publishes the marks of one run's batches from a coroutine of its own. The marks gathered
     * are published at most every [ProtonThumbnailWorkPolicy.PROGRESS_PUBLISH_INTERVAL_MILLIS]
     * across batches (a publisher per batch reset that interval per batch and made every batch
     * end wait for it), and once more when the run ends ([finish]) so nothing stored waits for
     * the next run.
     * Wake-ups are conflated: however many settles arrive during a wait, the next publication
     * carries them all.
     */
    private inner class MarkPublisher(
        private val userId: UserId,
    ) {
        private val marks = PendingMarks()
        private val lock = Mutex()
        private val wakeUps = Channel<Unit>(Channel.CONFLATED)
        private val job = publishScope.launch { drain() }

        suspend fun add(update: PendingMarks.() -> Unit) {
            lock.withLock { marks.update() }
            wakeUps.trySend(Unit)
        }

        /** Publishes whatever is left and returns once it has been published. */
        suspend fun finish() {
            wakeUps.close()
            job.join()
        }

        private suspend fun drain() {
            for (wakeUp in wakeUps) {
                val waitMillis =
                    lock.withLock {
                        ProtonThumbnailWorkPolicy.progressPublishWaitMillis(
                            marks.lastPublishedMillis,
                            clock.nowMillis(),
                        )
                    }
                if (waitMillis > 0L) delay(waitMillis)
                publish()
            }
            publish()
        }

        private suspend fun publish() {
            val snapshot = lock.withLock { marks.take(clock.nowMillis()) } ?: return
            if (snapshot.timeline.isNotEmpty() || snapshot.albumCovers.isNotEmpty() ||
                snapshot.albumPhotos.isNotEmpty()
            ) {
                availability.thumbnailsAvailable(
                    userId,
                    snapshot.timeline,
                    snapshot.albumCovers,
                    snapshot.albumPhotos,
                )
            }
            if (snapshot.previews.isNotEmpty()) availability.previewsAvailable(userId, snapshot.previews)
        }
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

        /** Hands over everything gathered and starts the next interval, or null when there is nothing. */
        fun take(publishedAtMillis: Long): PublishedMarks? {
            if (isEmpty()) return null
            val snapshot =
                PublishedMarks(timeline.toSet(), albumCovers.toSet(), albumPhotos.toSet(), previews.toSet())
            timeline.clear()
            albumCovers.clear()
            albumPhotos.clear()
            previews.clear()
            lastPublishedMillis = publishedAtMillis
            return snapshot
        }
    }

    private class PublishedMarks(
        val timeline: Set<String>,
        val albumCovers: Set<String>,
        val albumPhotos: Set<String>,
        val previews: Set<String>,
    )
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
