package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProtonThumbnailQueueEntry(
    val nodeUid: String,
    val sources: Set<String>,
    val priority: Int,
    val order: Long,
    val retryCount: Int = 0,
    val retryAtMillis: Long = 0L,
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
    private val dirtyUsers = mutableSetOf<String>()
    private val currentSectionNodeUids = mutableMapOf<String, Set<String>>()
    private val visibleNodeUids = mutableMapOf<String, Set<String>>()
    private val claimedNodeUids = mutableMapOf<String, MutableSet<String>>()

    suspend fun replaceSource(userId: String, source: String, pendingNodeUids: Collection<String>) {
        replaceSources(userId, mapOf(source to pendingNodeUids))
    }

    suspend fun replaceSources(
        userId: String,
        pendingNodeUidsBySource: Map<String, Collection<String>>,
        retainedAlbumNodeUids: Collection<String>? = null,
    ) {
        mutex.withLock {
            val entries = entries(userId)
            val replacedSources = pendingNodeUidsBySource.keys
            val retainedAlbumSources = retainedAlbumNodeUids?.mapTo(mutableSetOf()) { nodeUid ->
                "album:$nodeUid"
            }
            entries.replaceAll { _, entry ->
                entry.copy(
                    sources = entry.sources.filterTo(mutableSetOf()) { source ->
                        source !in replacedSources &&
                            (retainedAlbumSources == null ||
                                !source.startsWith("album:") ||
                                source in retainedAlbumSources)
                    },
                )
            }
            var order = nextOrder(entries.values) + pendingNodeUidsBySource.values.sumOf { it.size }
            val section = currentSectionNodeUids[userId].orEmpty()
            val visible = visibleNodeUids[userId].orEmpty()
            pendingNodeUidsBySource.forEach { (source, pendingNodeUids) ->
                pendingNodeUids.distinct().forEach { nodeUid ->
                    val existing = entries[nodeUid]
                    entries[nodeUid] = existing?.copy(sources = existing.sources + source)
                        ?: ProtonThumbnailQueueEntry(
                            nodeUid = nodeUid,
                            sources = setOf(source),
                            priority = when (nodeUid) {
                                in visible -> VISIBLE_PRIORITY
                                in section -> SECTION_PRIORITY
                                else -> BACKGROUND_PRIORITY
                            },
                            order = order--,
                        )
                }
            }
            entries.values.removeAll { entry -> entry.sources.isEmpty() }
            claimedNodeUids[userId]?.retainAll(entries.keys)
            persist(userId, entries)
        }
    }

    suspend fun prioritizeSection(userId: String, nodeUids: Collection<String>) {
        mutex.withLock {
            val section = nodeUids.toSet()
            currentSectionNodeUids[userId] = section
            val visible = visibleNodeUids[userId].orEmpty().intersect(section)
            if (visible.isEmpty()) visibleNodeUids.remove(userId) else visibleNodeUids[userId] = visible
            val entries = entries(userId)
            var order = nextOrder(entries.values) + section.size
            entries.replaceAll { nodeUid, entry ->
                when (nodeUid) {
                    in visible -> entry.copy(priority = VISIBLE_PRIORITY, order = order--)
                    in section -> entry.copy(priority = SECTION_PRIORITY, order = order--)
                    else -> entry.copy(priority = BACKGROUND_PRIORITY)
                }
            }
            dirtyUsers += userId
        }
    }

    suspend fun prioritizeVisible(userId: String, nodeUids: Collection<String>) {
        mutex.withLock {
            val visible = nodeUids.toSet()
            val previouslyVisible = visibleNodeUids.put(userId, visible).orEmpty()
            if (previouslyVisible == visible) return@withLock
            val section = currentSectionNodeUids[userId].orEmpty()
            val entries = entries(userId)
            entries.replaceAll { nodeUid, entry ->
                if (entry.priority != VISIBLE_PRIORITY) entry else entry.copy(
                    priority = if (nodeUid in section) SECTION_PRIORITY else BACKGROUND_PRIORITY,
                )
            }
            var order = nextOrder(entries.values) + visible.size
            visible.forEach { nodeUid ->
                entries[nodeUid]?.let { entry ->
                    entries[nodeUid] = entry.copy(
                        priority = VISIBLE_PRIORITY,
                        order = order--,
                        // A retry delay earned while off-screen must not leave a visible hole.
                        retryAtMillis = if (nodeUid in previouslyVisible) entry.retryAtMillis else 0L,
                    )
                }
            }
            dirtyUsers += userId
        }
    }

    suspend fun retryNow(userId: String, nodeUid: String, sources: Set<String>) {
        if (sources.isEmpty()) return
        mutex.withLock {
            val entries = entries(userId)
            val existing = entries[nodeUid]
            entries[nodeUid] = ProtonThumbnailQueueEntry(
                nodeUid = nodeUid,
                sources = existing?.sources.orEmpty() + sources,
                priority = VISIBLE_PRIORITY,
                order = nextOrder(entries.values),
                retryCount = existing?.retryCount ?: 0,
                retryAtMillis = 0L,
            )
            persist(userId, entries)
        }
    }

    suspend fun nextReady(userId: String): ProtonThumbnailQueueEntry? = mutex.withLock {
        val now = clock.nowMillis()
        entries(userId).values
            .asSequence()
            .filter { entry -> entry.retryAtMillis <= now }
            .sortedWith(compareBy<ProtonThumbnailQueueEntry> { it.priority }.thenByDescending { it.order })
            .firstOrNull()
    }

    suspend fun claimReady(userId: String, limit: Int): List<ProtonThumbnailQueueEntry> =
        claim(userId, limit, maximumPriority = Int.MAX_VALUE)

    suspend fun claimVisible(userId: String, limit: Int): List<ProtonThumbnailQueueEntry> =
        claim(userId, limit, maximumPriority = VISIBLE_PRIORITY)

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

    suspend fun complete(userId: String, nodeUid: String): Boolean = mutex.withLock {
        val removed = entries(userId).remove(nodeUid) != null
        claimedNodeUids[userId]?.remove(nodeUid)
        if (removed) dirtyUsers += userId
        removed
    }

    suspend fun defer(userId: String, nodeUid: String) {
        mutex.withLock {
            val entries = entries(userId)
            val entry = entries[nodeUid] ?: return@withLock
            val retryCount = entry.retryCount + 1
            entries[nodeUid] = entry.copy(
                retryCount = retryCount,
                retryAtMillis = clock.nowMillis() + retryDelayMillis(retryCount),
            )
            claimedNodeUids[userId]?.remove(nodeUid)
            persist(userId, entries)
        }
    }

    suspend fun hasPending(userId: String): Boolean = mutex.withLock {
        entries(userId).isNotEmpty()
    }

    suspend fun retainAlbumSources(userId: String, albumNodeUids: Collection<String>) {
        replaceSources(userId, emptyMap(), albumNodeUids)
    }

    suspend fun forget(userId: String) {
        mutex.withLock {
            entriesByUser.remove(userId)
            dirtyUsers.remove(userId)
            currentSectionNodeUids.remove(userId)
            visibleNodeUids.remove(userId)
            claimedNodeUids.remove(userId)
        }
    }

    suspend fun flush(userId: String) {
        mutex.withLock {
            if (userId !in dirtyUsers) return@withLock
            persist(userId, entries(userId))
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
        dirtyUsers.remove(userId)
    }

    private fun nextOrder(entries: Collection<ProtonThumbnailQueueEntry>): Long =
        (entries.maxOfOrNull(ProtonThumbnailQueueEntry::order) ?: 0L) + 1L

    private suspend fun claim(
        userId: String,
        limit: Int,
        maximumPriority: Int,
    ): List<ProtonThumbnailQueueEntry> = mutex.withLock {
        require(limit > 0) { "Thumbnail claim limit must be positive" }
        val now = clock.nowMillis()
        val claimed = claimedNodeUids.getOrPut(userId, ::mutableSetOf)
        entries(userId).values
            .asSequence()
            .filter { entry -> entry.retryAtMillis <= now }
            .filter { entry -> entry.priority <= maximumPriority }
            .filterNot { entry -> entry.nodeUid in claimed }
            .sortedWith(compareBy<ProtonThumbnailQueueEntry> { it.priority }.thenByDescending { it.order })
            .take(limit)
            .toList()
            .onEach { entry -> claimed += entry.nodeUid }
    }

    private fun retryDelayMillis(retryCount: Int): Long {
        val multiplier = 1L shl (retryCount - 1).coerceIn(0, MAX_RETRY_SHIFT)
        return (BASE_RETRY_MILLIS * multiplier).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private companion object {
        const val VISIBLE_PRIORITY = 0
        const val SECTION_PRIORITY = 10
        const val BACKGROUND_PRIORITY = 100
        const val BASE_RETRY_MILLIS = 30_000L
        const val MAX_RETRY_MILLIS = 15L * 60L * 1_000L
        const val MAX_RETRY_SHIFT = 5
    }
}
