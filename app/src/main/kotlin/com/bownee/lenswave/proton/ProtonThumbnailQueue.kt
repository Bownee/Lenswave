package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Qualifier
import javax.inject.Singleton

/** Identifies one persisted download queue; each has its own file under the user's metadata. */
internal enum class ProtonQueueName(
    val fileName: String,
) {
    THUMBNAILS("thumbnail-queue.json"),
    PREVIEWS("preview-queue.json"),
}

/** The queue of grid thumbnails; always drained before [PreviewQueue]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ThumbnailQueue

/** The queue of screen-sized previews for the viewer. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class PreviewQueue

@Module
@InstallIn(SingletonComponent::class)
internal object ProtonQueueModule {
    @Provides
    @Singleton
    @ThumbnailQueue
    fun provideThumbnailQueue(
        store: ProtonThumbnailQueueStore,
        clock: LenswaveClock,
    ): ProtonThumbnailQueue = ProtonThumbnailQueue(store, clock, ProtonQueueName.THUMBNAILS)

    @Provides
    @Singleton
    @PreviewQueue
    fun providePreviewQueue(
        store: ProtonThumbnailQueueStore,
        clock: LenswaveClock,
    ): ProtonThumbnailQueue = ProtonThumbnailQueue(store, clock, ProtonQueueName.PREVIEWS)
}

internal data class ProtonThumbnailQueueEntry(
    val nodeUid: String,
    val sourceCaptureTimes: Map<String, Long>,
    val retryCount: Int = 0,
    val retryAtMillis: Long = 0L,
) {
    val sources: Set<String> get() = sourceCaptureTimes.keys
    val captureTimeEpochSeconds: Long
        get() = sourceCaptureTimes.values.maxOrNull() ?: Long.MIN_VALUE
}

internal data class ProtonThumbnailCandidate(
    val nodeUid: String,
    val captureTimeEpochSeconds: Long,
)

internal sealed interface ProtonThumbnailQueueStep {
    /** A batch was claimed and settled; successes and failures alike are recorded in the queue. */
    data object Processed : ProtonThumbnailQueueStep

    /** [retryAfterMillis] is how long until a backed-off entry is claimable again, when any is pending. */
    data class Idle(
        val hasPending: Boolean,
        val retryAfterMillis: Long? = null,
        /** Previews are waiting for the charger; not pending for this run, but a charging run is due. */
        val previewsDeferred: Boolean = false,
    ) : ProtonThumbnailQueueStep
}

/**
 * A persistent, per-user download queue ordered newest capture time first. One instance serves
 * thumbnails and another previews; [name] selects which file each persists to.
 *
 * The in-memory map is authoritative. Changes mark the user's queue dirty and are written back
 * on the schedule [ProtonQueueFlushPolicy] decides; [flush] forces the write. Serialization and
 * encryption happen from a snapshot outside [mutex], and [writeMutex] keeps writes in generation
 * order so an older snapshot can never land on top of a newer one.
 */
internal class ProtonThumbnailQueue(
    private val store: ProtonThumbnailQueueStore,
    private val clock: LenswaveClock,
    private val name: ProtonQueueName = ProtonQueueName.THUMBNAILS,
    private val flushScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private val writeMutex = Mutex()
    private val entriesByUser = mutableMapOf<String, LinkedHashMap<String, ProtonThumbnailQueueEntry>>()
    private val claimedNodeUids = mutableMapOf<String, MutableSet<String>>()
    private val persistence = mutableMapOf<String, UserPersistence>()

    suspend fun replaceSource(
        userId: String,
        source: String,
        pendingCandidates: Collection<ProtonThumbnailCandidate>,
    ) {
        replaceSources(userId, mapOf(source to pendingCandidates))
    }

    /** A source replacement is a full reconciliation, so it is written through at once. */
    suspend fun replaceSources(
        userId: String,
        pendingCandidatesBySource: Map<String, Collection<ProtonThumbnailCandidate>>,
        retainedAlbumNodeUids: Collection<String>? = null,
    ) {
        mutex.withLock {
            val entries = entries(userId)
            val replacedSources = pendingCandidatesBySource.keys
            val retainedAlbumSources =
                retainedAlbumNodeUids?.mapTo(
                    mutableSetOf(),
                    ProtonSyncKeys.QueueSource::albumPhotos,
                )
            entries.replaceAll { _, entry ->
                entry.copy(
                    sourceCaptureTimes =
                        entry.sourceCaptureTimes.filterKeys { source ->
                            source !in replacedSources &&
                                (
                                    retainedAlbumSources == null ||
                                        !ProtonSyncKeys.QueueSource.isAlbumPhotos(source) ||
                                        source in retainedAlbumSources
                                )
                        },
                )
            }
            pendingCandidatesBySource.forEach { (source, candidates) ->
                candidates.distinctBy(ProtonThumbnailCandidate::nodeUid).forEach { candidate ->
                    val existing = entries[candidate.nodeUid]
                    entries[candidate.nodeUid] = existing?.copy(
                        sourceCaptureTimes =
                            existing.sourceCaptureTimes +
                                (source to candidate.captureTimeEpochSeconds),
                    )
                        ?: ProtonThumbnailQueueEntry(
                            nodeUid = candidate.nodeUid,
                            sourceCaptureTimes = mapOf(source to candidate.captureTimeEpochSeconds),
                        )
                }
            }
            entries.values.removeAll { entry -> entry.sources.isEmpty() }
            claimedNodeUids[userId]?.retainAll(entries.keys)
            markChanged(userId)
        }
        flush(userId)
    }

    suspend fun retryNow(
        userId: String,
        candidate: ProtonThumbnailCandidate,
        sources: Set<String>,
    ) {
        if (sources.isEmpty()) return
        mutex.withLock {
            val entries = entries(userId)
            val existing = entries[candidate.nodeUid]
            val sourceCaptureTimes = existing?.sourceCaptureTimes.orEmpty().toMutableMap()
            sources.forEach { source ->
                sourceCaptureTimes.putIfAbsent(source, candidate.captureTimeEpochSeconds)
            }
            entries[candidate.nodeUid] =
                ProtonThumbnailQueueEntry(
                    nodeUid = candidate.nodeUid,
                    sourceCaptureTimes = sourceCaptureTimes,
                    retryCount = existing?.retryCount ?: 0,
                    retryAtMillis = 0L,
                )
            markChanged(userId)
        }
    }

    suspend fun claimReady(
        userId: String,
        limit: Int,
    ): List<ProtonThumbnailQueueEntry> =
        mutex.withLock {
            require(limit > 0) { "Thumbnail claim limit must be positive" }
            val now = clock.nowMillis()
            val claimed = claimedNodeUids.getOrPut(userId, ::mutableSetOf)
            val ready =
                entries(userId)
                    .values
                    .asSequence()
                    .filter { entry -> entry.retryAtMillis <= now }
                    .filterNot { entry -> entry.nodeUid in claimed }
                    .asIterable()
            ProtonQueueSelectionPolicy
                .takeFirst(ready, limit, NEWEST_FIRST)
                .onEach { entry -> claimed += entry.nodeUid }
        }

    /**
     * Removes successful entries and reschedules failed ones with backoff. An entry that has
     * failed [MAX_RETRY_COUNT] times is dropped until the next sync queues it afresh, so one bad
     * photo cannot keep the worker retrying for days.
     */
    suspend fun settle(
        userId: String,
        successfulNodeUids: Set<String>,
        failedNodeUids: Set<String>,
    ): List<ProtonThumbnailQueueEntry> =
        mutex.withLock {
            require(successfulNodeUids.intersect(failedNodeUids).isEmpty()) {
                "A thumbnail cannot succeed and fail in the same batch"
            }
            val entries = entries(userId)
            val completed = successfulNodeUids.mapNotNull(entries::remove)
            val now = clock.nowMillis()
            var dropped = 0
            failedNodeUids.forEach { nodeUid ->
                val entry = entries[nodeUid] ?: return@forEach
                val retryCount = entry.retryCount + 1
                if (retryCount >= MAX_RETRY_COUNT) {
                    entries.remove(nodeUid)
                    dropped++
                    return@forEach
                }
                entries[nodeUid] =
                    entry.copy(
                        retryCount = retryCount,
                        retryAtMillis = now + retryDelayMillis(retryCount),
                    )
            }
            val settled = successfulNodeUids + failedNodeUids
            claimedNodeUids[userId]?.let { claimed ->
                claimed.removeAll(settled)
                if (claimed.isEmpty()) claimedNodeUids.remove(userId)
            }
            val changes = completed.size + dropped + failedNodeUids.count(entries::containsKey)
            if (changes > 0) markChanged(userId, changes)
            completed
        }

    suspend fun release(
        userId: String,
        nodeUids: Collection<String>,
    ) {
        mutex.withLock {
            claimedNodeUids[userId]?.let { claimed ->
                claimed.removeAll(nodeUids.toSet())
                if (claimed.isEmpty()) claimedNodeUids.remove(userId)
            }
        }
    }

    suspend fun hasPending(userId: String): Boolean =
        mutex.withLock {
            entries(userId).isNotEmpty()
        }

    suspend fun pendingCount(userId: String): Int =
        mutex.withLock {
            entries(userId).size
        }

    /** How long until the soonest backed-off entry is claimable again (0 if now), or null when empty. */
    suspend fun retryDelayMillis(userId: String): Long? =
        mutex.withLock {
            entries(userId)
                .values
                .minOfOrNull(ProtonThumbnailQueueEntry::retryAtMillis)
                ?.let { retryAt -> (retryAt - clock.nowMillis()).coerceAtLeast(0L) }
        }

    /**
     * Drops the user's queue from memory without writing it: the caller is erasing the user's
     * files, and a write landing afterwards would leave a queue behind for an account that is
     * gone. Waits for a write already in progress so nothing lands after this returns.
     */
    suspend fun forget(userId: String) {
        mutex.withLock {
            entriesByUser.remove(userId)
            claimedNodeUids.remove(userId)
            persistence.remove(userId)?.scheduledFlush?.cancel()
        }
        writeMutex.withLock {}
    }

    /** Writes every unflushed change for [userId] before returning; a no-op when nothing changed. */
    suspend fun flush(userId: String) {
        val snapshot =
            mutex.withLock {
                val state = persistence[userId] ?: return
                state.scheduledFlush?.let { scheduled ->
                    state.scheduledFlush = null
                    if (scheduled !== currentCoroutineContext()[Job]) scheduled.cancel()
                }
                if (state.generation == state.writtenGeneration) return
                Snapshot(entries(userId).values.toList(), state.generation)
            }
        writeMutex.withLock {
            val state = mutex.withLock { persistence[userId] } ?: return
            if (ProtonQueueFlushPolicy.isStale(snapshot.generation, state.writtenGeneration)) return
            try {
                store.writeQueue(userId, name, snapshot.entries)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // The queue stays dirty; the next change or forced flush tries again.
                LenswaveDiagnostics.reportFailure(LenswaveOperation.DOWNLOAD_QUEUE_PERSIST, error)
                return
            }
            mutex.withLock {
                if (state.writtenGeneration < snapshot.generation) state.writtenGeneration = snapshot.generation
            }
        }
    }

    /** Records [changes] in-memory edits and schedules the write [ProtonQueueFlushPolicy] asks for. */
    private fun markChanged(
        userId: String,
        changes: Int = 1,
    ) {
        val state = persistence.getOrPut(userId, ::UserPersistence)
        state.generation += changes
        val unflushed = (state.generation - state.writtenGeneration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val delayMillis =
            ProtonQueueFlushPolicy.flushDelayMillis(unflushed, flushScheduled = state.scheduledFlush != null)
                ?: return
        state.scheduledFlush?.cancel()
        state.scheduledFlush =
            flushScope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                flush(userId)
            }
    }

    private fun entries(userId: String): LinkedHashMap<String, ProtonThumbnailQueueEntry> =
        entriesByUser.getOrPut(userId) {
            store.readQueue(userId, name).associateByTo(linkedMapOf(), ProtonThumbnailQueueEntry::nodeUid)
        }

    private fun retryDelayMillis(retryCount: Int): Long {
        val multiplier = 1L shl (retryCount - 1).coerceIn(0, MAX_RETRY_SHIFT)
        return (BASE_RETRY_MILLIS * multiplier).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private class UserPersistence {
        /** Bumped per in-memory change; [writtenGeneration] trails it until a flush catches up. */
        var generation = 0L
        var writtenGeneration = 0L
        var scheduledFlush: Job? = null
    }

    private class Snapshot(
        val entries: List<ProtonThumbnailQueueEntry>,
        val generation: Long,
    )

    companion object {
        /** Six failures span roughly half an hour of backoff before an entry is given up on. */
        const val MAX_RETRY_COUNT = 6
        private const val BASE_RETRY_MILLIS = 30_000L
        private const val MAX_RETRY_MILLIS = 15L * 60L * 1_000L
        private const val MAX_RETRY_SHIFT = 5
        private val NEWEST_FIRST =
            compareByDescending(ProtonThumbnailQueueEntry::captureTimeEpochSeconds)
                .thenBy(ProtonThumbnailQueueEntry::nodeUid)
    }
}
