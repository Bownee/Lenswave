package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
 */
internal class ProtonThumbnailQueue(
    private val store: ProtonThumbnailQueueStore,
    private val clock: LenswaveClock,
    private val name: ProtonQueueName = ProtonQueueName.THUMBNAILS,
) {
    private val mutex = Mutex()
    private val entriesByUser = mutableMapOf<String, LinkedHashMap<String, ProtonThumbnailQueueEntry>>()
    private val claimedNodeUids = mutableMapOf<String, MutableSet<String>>()

    suspend fun replaceSource(
        userId: String,
        source: String,
        pendingCandidates: Collection<ProtonThumbnailCandidate>,
    ) {
        replaceSources(userId, mapOf(source to pendingCandidates))
    }

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
            persist(userId, entries)
        }
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
            persist(userId, entries)
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
            entries(userId)
                .values
                .asSequence()
                .filter { entry -> entry.retryAtMillis <= now }
                .filterNot { entry -> entry.nodeUid in claimed }
                .sortedWith(NEWEST_FIRST)
                .take(limit)
                .toList()
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
            if (completed.isNotEmpty() || dropped > 0 || failedNodeUids.any(entries::containsKey)) {
                persist(userId, entries)
            }
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

    suspend fun forget(userId: String) {
        mutex.withLock {
            entriesByUser.remove(userId)
            claimedNodeUids.remove(userId)
        }
    }

    private fun entries(userId: String): LinkedHashMap<String, ProtonThumbnailQueueEntry> =
        entriesByUser.getOrPut(userId) {
            store.readQueue(userId, name).associateByTo(linkedMapOf(), ProtonThumbnailQueueEntry::nodeUid)
        }

    private fun persist(
        userId: String,
        entries: Map<String, ProtonThumbnailQueueEntry>,
    ) {
        store.writeQueue(userId, name, entries.values.toList())
    }

    private fun retryDelayMillis(retryCount: Int): Long {
        val multiplier = 1L shl (retryCount - 1).coerceIn(0, MAX_RETRY_SHIFT)
        return (BASE_RETRY_MILLIS * multiplier).coerceAtMost(MAX_RETRY_MILLIS)
    }

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
