package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    data object Downloaded : ProtonThumbnailQueueStep
    data object Failed : ProtonThumbnailQueueStep
    data class Idle(val hasPending: Boolean) : ProtonThumbnailQueueStep
}

@Singleton
internal class ProtonThumbnailQueue @Inject constructor(
    private val store: ProtonThumbnailQueueStore,
    private val clock: LenswaveClock,
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
            val retainedAlbumSources = retainedAlbumNodeUids?.mapTo(mutableSetOf()) { nodeUid ->
                "album:$nodeUid"
            }
            entries.replaceAll { _, entry ->
                entry.copy(
                    sourceCaptureTimes = entry.sourceCaptureTimes.filterKeys { source ->
                        source !in replacedSources &&
                            (retainedAlbumSources == null ||
                                !source.startsWith("album:") ||
                                source in retainedAlbumSources)
                    },
                )
            }
            pendingCandidatesBySource.forEach { (source, candidates) ->
                candidates.distinctBy(ProtonThumbnailCandidate::nodeUid).forEach { candidate ->
                    val existing = entries[candidate.nodeUid]
                    entries[candidate.nodeUid] = existing?.copy(
                        sourceCaptureTimes = existing.sourceCaptureTimes +
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
            entries[candidate.nodeUid] = ProtonThumbnailQueueEntry(
                nodeUid = candidate.nodeUid,
                sourceCaptureTimes = sourceCaptureTimes,
                retryCount = existing?.retryCount ?: 0,
                retryAtMillis = 0L,
            )
            persist(userId, entries)
        }
    }

    suspend fun claimReady(userId: String, limit: Int): List<ProtonThumbnailQueueEntry> =
        claim(userId, limit)

    suspend fun settle(
        userId: String,
        successfulNodeUids: Set<String>,
        failedNodeUids: Set<String>,
    ): List<ProtonThumbnailQueueEntry> = mutex.withLock {
        require(successfulNodeUids.intersect(failedNodeUids).isEmpty()) {
            "A thumbnail cannot succeed and fail in the same batch"
        }
        val entries = entries(userId)
        val completed = successfulNodeUids.mapNotNull(entries::remove)
        val now = clock.nowMillis()
        failedNodeUids.forEach { nodeUid ->
            val entry = entries[nodeUid] ?: return@forEach
            val retryCount = entry.retryCount + 1
            entries[nodeUid] = entry.copy(
                retryCount = retryCount,
                retryAtMillis = now + retryDelayMillis(retryCount),
            )
        }
        val settled = successfulNodeUids + failedNodeUids
        claimedNodeUids[userId]?.let { claimed ->
            claimed.removeAll(settled)
            if (claimed.isEmpty()) claimedNodeUids.remove(userId)
        }
        if (completed.isNotEmpty() || failedNodeUids.any(entries::containsKey)) {
            persist(userId, entries)
        }
        completed
    }

    suspend fun release(userId: String, nodeUids: Collection<String>) {
        mutex.withLock {
            claimedNodeUids[userId]?.let { claimed ->
                claimed.removeAll(nodeUids.toSet())
                if (claimed.isEmpty()) claimedNodeUids.remove(userId)
            }
        }
    }

    suspend fun hasPending(userId: String): Boolean = mutex.withLock {
        entries(userId).isNotEmpty()
    }

    suspend fun pendingCount(userId: String): Int = mutex.withLock {
        entries(userId).size
    }

    suspend fun retainAlbumSources(userId: String, albumNodeUids: Collection<String>) {
        replaceSources(userId, emptyMap(), albumNodeUids)
    }

    suspend fun forget(userId: String) {
        mutex.withLock {
            entriesByUser.remove(userId)
            claimedNodeUids.remove(userId)
        }
    }

    private fun entries(userId: String): LinkedHashMap<String, ProtonThumbnailQueueEntry> =
        entriesByUser.getOrPut(userId) {
            store.readThumbnailQueue(userId).associateByTo(linkedMapOf(), ProtonThumbnailQueueEntry::nodeUid)
        }

    private fun persist(
        userId: String,
        entries: Map<String, ProtonThumbnailQueueEntry>,
    ) {
        store.writeThumbnailQueue(userId, entries.values.toList())
    }

    private suspend fun claim(
        userId: String,
        limit: Int,
    ): List<ProtonThumbnailQueueEntry> = mutex.withLock {
        require(limit > 0) { "Thumbnail claim limit must be positive" }
        val now = clock.nowMillis()
        val claimed = claimedNodeUids.getOrPut(userId, ::mutableSetOf)
        entries(userId).values
            .asSequence()
            .filter { entry -> entry.retryAtMillis <= now }
            .filterNot { entry -> entry.nodeUid in claimed }
            .sortedWith(NEWEST_FIRST)
            .take(limit)
            .toList()
            .onEach { entry -> claimed += entry.nodeUid }
    }

    private fun retryDelayMillis(retryCount: Int): Long {
        val multiplier = 1L shl (retryCount - 1).coerceIn(0, MAX_RETRY_SHIFT)
        return (BASE_RETRY_MILLIS * multiplier).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private companion object {
        const val BASE_RETRY_MILLIS = 30_000L
        const val MAX_RETRY_MILLIS = 15L * 60L * 1_000L
        const val MAX_RETRY_SHIFT = 5
        val NEWEST_FIRST = compareByDescending(ProtonThumbnailQueueEntry::captureTimeEpochSeconds)
            .thenBy(ProtonThumbnailQueueEntry::nodeUid)
    }
}
