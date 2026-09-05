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
    /**
     * Consecutive connection failures since the last ordinary retry step; see
     * [ProtonThumbnailQueue.MAX_CONSECUTIVE_NETWORK_RETRIES]. Not in the queue file yet: a
     * process that restarts starts the count over, which only allows a few more short retries.
     */
    val networkRetryCount: Int = 0,
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
    private val onWriteFailure: (Throwable) -> Unit = { error ->
        LenswaveDiagnostics.reportFailure(LenswaveOperation.DOWNLOAD_QUEUE_PERSIST, error)
    },
) {
    private val mutex = Mutex()
    private val writeMutex = Mutex()
    private val entriesByUser = mutableMapOf<String, LinkedHashMap<String, ProtonThumbnailQueueEntry>>()
    private val claimedNodeUids = mutableMapOf<String, MutableSet<String>>()
    private val persistence = mutableMapOf<String, UserPersistence>()

    /**
     * Nodes [settle] dropped (no such rendition, or the last retry spent), with when each may be
     * queued again: every reconciliation re-adds each photo without a rendition, so without this
     * a dropped node was back in the queue at the next sync and asked of the SDK forever. Kept in
     * memory for the life of the process; the queue file's format does not carry it yet.
     */
    private val suppressedUntilByUser = mutableMapOf<String, MutableMap<String, Long>>()

    /**
     * Nodes [settle] parked with [ThumbnailFailureKind.PREVIEW_DEFERRED]: their thumbnail can
     * only be had as a preview, and previews were not allowed when they were tried. While
     * previews stay disallowed they are neither claimable nor pending, so a run does not claim,
     * ask the SDK about and park them again until its deadline; once previews are allowed
     * they are claimable at once. In memory only: a process that restarts finds them with the
     * deferral horizon in [ProtonThumbnailQueueEntry.retryAtMillis] and parks them again on
     * the first try.
     */
    private val awaitingPreviewsByUser = mutableMapOf<String, MutableSet<String>>()

    /** Bumped by [forget] so a read that was in progress for that user is not installed. */
    private var forgetCount = 0L

    suspend fun replaceSource(
        userId: String,
        source: String,
        pendingCandidates: Collection<ProtonThumbnailCandidate>,
    ) {
        replaceSources(userId, mapOf(source to pendingCandidates))
    }

    /**
     * A source replacement is a full reconciliation, and one that usually changes nothing: it
     * runs several times per app open for both queues (activation housekeeping, the timeline
     * sync moments later, album covers on the library). So the write is left to the debounce
     * like any other change, and a replacement that leaves every entry as it was is not a
     * change at all: no generation bump, no write.
     */
    suspend fun replaceSources(
        userId: String,
        pendingCandidatesBySource: Map<String, Collection<ProtonThumbnailCandidate>>,
        retainedAlbumNodeUids: Collection<String>? = null,
    ) {
        hydrate(userId)
        mutex.withLock {
            val entries = entries(userId)
            val replacedSources = pendingCandidatesBySource.keys
            val retainedAlbumSources =
                retainedAlbumNodeUids?.mapTo(
                    mutableSetOf(),
                    ProtonSyncKeys.QueueSource::albumPhotos,
                )

            fun isRemoved(source: String): Boolean =
                source in replacedSources ||
                    (
                        retainedAlbumSources != null &&
                            ProtonSyncKeys.QueueSource.isAlbumPhotos(source) &&
                            source !in retainedAlbumSources
                    )
            // Every entry touched below is remembered as it was (absent ones as null), so the
            // replacement can tell afterwards whether it amounted to a change. Only touched
            // entries are compared; the rest of the queue is never copied or visited twice.
            val originals = HashMap<String, ProtonThumbnailQueueEntry?>()
            // Most entries keep every source they have; copying each of them on every
            // reconciliation (several per app open, for both queues) is work for nothing.
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val slot = iterator.next()
                val entry = slot.value
                if (entry.sources.none(::isRemoved)) continue
                originals.putIfAbsent(entry.nodeUid, entry)
                val kept = entry.sourceCaptureTimes.filterKeys { source -> !isRemoved(source) }
                if (kept.isEmpty()) {
                    iterator.remove()
                } else {
                    slot.setValue(entry.copy(sourceCaptureTimes = kept))
                }
            }
            val suppressed = suppressedNodeUids(userId)
            pendingCandidatesBySource.forEach { (source, candidates) ->
                candidates.distinctBy(ProtonThumbnailCandidate::nodeUid).forEach { candidate ->
                    if (candidate.nodeUid in suppressed) return@forEach
                    val existing = entries[candidate.nodeUid]
                    originals.putIfAbsent(candidate.nodeUid, existing)
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
            claimedNodeUids[userId]?.retainAll(entries.keys)
            awaitingPreviewsByUser[userId]?.retainAll(entries.keys)
            val changed = originals.any { (nodeUid, original) -> entries[nodeUid] != original }
            if (changed) markChanged(userId)
        }
    }

    suspend fun retryNow(
        userId: String,
        candidate: ProtonThumbnailCandidate,
        sources: Set<String>,
    ) {
        if (sources.isEmpty()) return
        hydrate(userId)
        mutex.withLock {
            val entries = entries(userId)
            // An explicit ask (the grid failed to decode what is stored) outranks a drop, and
            // a wait for previews: what is stored decoded once, so a thumbnail exists.
            suppressedUntilByUser[userId]?.remove(candidate.nodeUid)
            awaitingPreviewsByUser[userId]?.remove(candidate.nodeUid)
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

    /**
     * Claims up to [limit] entries that are due, newest capture first. [previewsAllowed] says
     * whether the run may fetch previews: the entries waiting for that are claimable at once
     * when it may, and not at all when it may not.
     */
    suspend fun claimReady(
        userId: String,
        limit: Int,
        previewsAllowed: Boolean = true,
    ): List<ProtonThumbnailQueueEntry> {
        require(limit > 0) { "Thumbnail claim limit must be positive" }
        hydrate(userId)
        return mutex.withLock {
            val now = clock.nowMillis()
            val claimed = claimedNodeUids.getOrPut(userId, ::mutableSetOf)
            val awaitingPreviews = awaitingPreviewsByUser[userId]
            val ready =
                entries(userId)
                    .values
                    .asSequence()
                    .filter { entry ->
                        readyAtMillis(entry, awaitingPreviews, previewsAllowed)?.let { it <= now } ==
                            true
                    }.filterNot { entry -> entry.nodeUid in claimed }
                    .asIterable()
            ProtonQueueSelectionPolicy
                .takeFirst(ready, limit, NEWEST_FIRST)
                .onEach { entry ->
                    claimed += entry.nodeUid
                    awaitingPreviews?.remove(entry.nodeUid)
                }
        }
    }

    /**
     * When [entry] is due for a run that may or may not fetch previews: now for a parked entry
     * once previews are allowed, never (null) for one while they are not, its own backoff
     * otherwise.
     */
    private fun readyAtMillis(
        entry: ProtonThumbnailQueueEntry,
        awaitingPreviews: Set<String>?,
        previewsAllowed: Boolean,
    ): Long? =
        when {
            awaitingPreviews == null || entry.nodeUid !in awaitingPreviews -> entry.retryAtMillis
            previewsAllowed -> 0L
            else -> null
        }

    /** [settle] with every failure treated as transient. */
    suspend fun settle(
        userId: String,
        successfulNodeUids: Set<String>,
        failedNodeUids: Set<String>,
    ): List<ProtonThumbnailQueueEntry> =
        settle(userId, successfulNodeUids, failedNodeUids.associateWith { ThumbnailFailureKind.OTHER })

    /**
     * Removes successful entries and reschedules failed ones with backoff. An entry that has
     * failed [MAX_RETRY_COUNT] times is dropped, so one bad photo cannot keep the worker retrying
     * for days; one Proton has no such rendition for ([ThumbnailFailureKind.NOT_FOUND]) is
     * dropped at once, since retrying cannot help and every retry is a full SDK enumeration. A
     * dropped node stays out of the queue for [SUPPRESSION_MILLIS] however often the listings
     * are reconciled; [retryNow] lifts that. A node the network failed under
     * ([ThumbnailFailureKind.TRANSIENT_NETWORK]) is not at fault: it waits [NETWORK_RETRY_MILLIS]
     * and keeps its retry count, so a connection lost mid-batch costs no node a backoff step;
     * but only [MAX_CONSECUTIVE_NETWORK_RETRIES] times in a row, after which the failure is
     * treated as any other, or a node that always fails that way would be asked every few
     * seconds for good. A node whose thumbnail can only be had as a preview while previews are not allowed
     * ([ThumbnailFailureKind.PREVIEW_DEFERRED]) is parked: it keeps its retry count, waits
     * [PREVIEW_DEFERRAL_MILLIS] for a process that forgets the parking, and is otherwise
     * claimable exactly when previews are ([claimReady]).
     */
    suspend fun settle(
        userId: String,
        successfulNodeUids: Set<String>,
        failures: Map<String, ThumbnailFailureKind>,
    ): List<ProtonThumbnailQueueEntry> {
        val failedNodeUids = failures.keys
        require(successfulNodeUids.intersect(failedNodeUids).isEmpty()) {
            "A thumbnail cannot succeed and fail in the same batch"
        }
        hydrate(userId)
        return mutex.withLock {
            val entries = entries(userId)
            val completed = successfulNodeUids.mapNotNull(entries::remove)
            val now = clock.nowMillis()
            var dropped = 0
            failures.forEach { (nodeUid, kind) ->
                val entry = entries[nodeUid] ?: return@forEach
                if (kind == ThumbnailFailureKind.TRANSIENT_NETWORK) {
                    // A few short retries are free; a connection that keeps failing for one
                    // node is not the connection, and from here the failure is an ordinary one.
                    val networkRetryCount = entry.networkRetryCount + 1
                    if (networkRetryCount < MAX_CONSECUTIVE_NETWORK_RETRIES) {
                        entries[nodeUid] =
                            entry.copy(
                                networkRetryCount = networkRetryCount,
                                retryAtMillis = now + NETWORK_RETRY_MILLIS,
                            )
                        return@forEach
                    }
                }
                if (kind == ThumbnailFailureKind.PREVIEW_DEFERRED) {
                    entries[nodeUid] = entry.copy(retryAtMillis = now + PREVIEW_DEFERRAL_MILLIS)
                    awaitingPreviewsByUser.getOrPut(userId, ::mutableSetOf) += nodeUid
                    return@forEach
                }
                val retryCount = entry.retryCount + 1
                if (kind == ThumbnailFailureKind.NOT_FOUND || retryCount >= MAX_RETRY_COUNT) {
                    entries.remove(nodeUid)
                    suppressedUntilByUser.getOrPut(userId, ::mutableMapOf)[nodeUid] = now + SUPPRESSION_MILLIS
                    dropped++
                    return@forEach
                }
                entries[nodeUid] =
                    entry.copy(
                        retryCount = retryCount,
                        networkRetryCount = 0,
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

    /** Gives back every claim of the user; for claims a batch that never settled left behind. */
    suspend fun releaseAll(userId: String) {
        mutex.withLock { claimedNodeUids.remove(userId) }
    }

    /**
     * Whether a run that may ([previewsAllowed]) or may not fetch previews has entries left to
     * serve; the entries parked for previews do not count while previews are not allowed.
     */
    suspend fun hasPending(
        userId: String,
        previewsAllowed: Boolean = true,
    ): Boolean {
        hydrate(userId)
        return mutex.withLock {
            val entries = entries(userId)
            val awaitingPreviews = awaitingPreviewsByUser[userId]
            if (previewsAllowed || awaitingPreviews == null) {
                entries.isNotEmpty()
            } else {
                entries.keys.any { nodeUid -> nodeUid !in awaitingPreviews }
            }
        }
    }

    /** Whether any entry is parked until a run may fetch previews; see [ThumbnailFailureKind.PREVIEW_DEFERRED]. */
    suspend fun hasEntriesAwaitingPreviews(userId: String): Boolean {
        hydrate(userId)
        return mutex.withLock { awaitingPreviewsByUser[userId]?.isNotEmpty() == true }
    }

    /** Every entry, parked and claimed ones included: what is still to be downloaded eventually. */
    suspend fun pendingCount(userId: String): Int {
        hydrate(userId)
        return mutex.withLock { entries(userId).size }
    }

    /**
     * How long until the soonest backed-off entry is claimable again (0 if now), or null when
     * nothing is; entries parked for previews are due now when [previewsAllowed], else left out.
     */
    suspend fun retryDelayMillis(
        userId: String,
        previewsAllowed: Boolean = true,
    ): Long? {
        hydrate(userId)
        return mutex.withLock {
            val awaitingPreviews = awaitingPreviewsByUser[userId]
            entries(userId)
                .values
                .mapNotNull { entry -> readyAtMillis(entry, awaitingPreviews, previewsAllowed) }
                .minOrNull()
                ?.let { retryAt -> (retryAt - clock.nowMillis()).coerceAtLeast(0L) }
        }
    }

    /**
     * Drops the user's queue from memory without writing it: the caller is erasing the user's
     * files, and a write landing afterwards would leave a queue behind for an account that is
     * gone. Waits for a write already in progress so nothing lands after this returns.
     */
    suspend fun forget(userId: String) {
        mutex.withLock {
            forgetCount++
            entriesByUser.remove(userId)
            claimedNodeUids.remove(userId)
            suppressedUntilByUser.remove(userId)
            awaitingPreviewsByUser.remove(userId)
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
                // The queue stays dirty; a retry is scheduled so the state is not stuck on disk
                // until the next change happens to come along.
                scheduleRetryAfterFailedWrite(userId, error)
                return
            }
            mutex.withLock {
                if (state.writtenGeneration < snapshot.generation) state.writtenGeneration = snapshot.generation
                state.consecutiveWriteFailures = 0
            }
        }
    }

    private suspend fun scheduleRetryAfterFailedWrite(
        userId: String,
        error: Throwable,
    ) {
        val report =
            mutex.withLock {
                // Forgotten meanwhile: the user's files are gone, and so is the reason to retry.
                val state = persistence[userId] ?: return
                state.consecutiveWriteFailures++
                // A chain that has given up leaves the queue dirty; the next change re-arms it.
                if (
                    state.scheduledFlush == null &&
                    ProtonQueueFlushPolicy.shouldRetryAfterFailedWrite(state.consecutiveWriteFailures)
                ) {
                    val delayMillis = ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(state.consecutiveWriteFailures)
                    state.scheduledFlush =
                        flushScope.launch {
                            delay(delayMillis)
                            flush(userId)
                        }
                }
                ProtonQueueFlushPolicy.shouldReportWriteFailure(state.consecutiveWriteFailures)
            }
        if (report) onWriteFailure(error)
    }

    /** Records [changes] in-memory edits and schedules the write [ProtonQueueFlushPolicy] asks for. */
    private fun markChanged(
        userId: String,
        changes: Int = 1,
    ) {
        val state = persistence.getOrPut(userId, ::UserPersistence)
        state.generation += changes
        // A change after the retry chain gave up starts a fresh, equally bounded chain.
        if (!ProtonQueueFlushPolicy.shouldRetryAfterFailedWrite(state.consecutiveWriteFailures)) {
            state.consecutiveWriteFailures = 0
        }
        val unflushed = (state.generation - state.writtenGeneration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val delayMillis =
            ProtonQueueFlushPolicy.flushDelayMillis(
                unflushed,
                flushScheduled = state.scheduledFlush != null,
                entryCount = entriesByUser[userId]?.size ?: 0,
            ) ?: return
        state.scheduledFlush?.cancel()
        state.scheduledFlush =
            flushScope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                flush(userId)
            }
    }

    /**
     * Reads the user's queue file on first use. Reading means decrypting and parsing, so it runs
     * outside [mutex] where every other operation would otherwise wait on it; a copy loaded by a
     * concurrent caller wins and this one is discarded, and a user forgotten during the read gets
     * nothing installed (the file being read is the one the caller is erasing).
     */
    private suspend fun hydrate(userId: String) {
        val forgetsBefore = mutex.withLock { if (userId in entriesByUser) return else forgetCount }
        val loaded = store.readQueue(userId, name).associateByTo(linkedMapOf(), ProtonThumbnailQueueEntry::nodeUid)
        mutex.withLock {
            if (forgetCount == forgetsBefore) entriesByUser.getOrPut(userId) { loaded }
        }
    }

    /** Only under [mutex] and after [hydrate]; a user forgotten in between starts empty. */
    private fun entries(userId: String): LinkedHashMap<String, ProtonThumbnailQueueEntry> =
        entriesByUser.getOrPut(userId, ::linkedMapOf)

    /** Only under [mutex]: the nodes still kept out of the queue, with the expired ones forgotten. */
    private fun suppressedNodeUids(userId: String): Set<String> {
        val suppressed = suppressedUntilByUser[userId] ?: return emptySet()
        val now = clock.nowMillis()
        suppressed.values.removeAll { until -> until <= now }
        if (suppressed.isEmpty()) suppressedUntilByUser.remove(userId)
        return suppressed.keys
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
        var consecutiveWriteFailures = 0
    }

    private class Snapshot(
        val entries: List<ProtonThumbnailQueueEntry>,
        val generation: Long,
    )

    companion object {
        /** Six failures span roughly half an hour of backoff before an entry is given up on. */
        const val MAX_RETRY_COUNT = 6

        /** How long a dropped node stays out of the queue whatever the listings say. */
        const val SUPPRESSION_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        /** The pause after a connection failure; long enough for the network to settle, no more. */
        const val NETWORK_RETRY_MILLIS = 10_000L

        /**
         * How many connection failures in a row are retried shortly and for free. The next one
         * takes an ordinary backoff step and starts the count over, so a node that fails this
         * way every time still climbs the ladder and is dropped after
         * [MAX_RETRY_COUNT] steps, instead of being asked every ten seconds forever.
         */
        const val MAX_CONSECUTIVE_NETWORK_RETRIES = 3

        /**
         * How long a node parked for previews stays out of reach of a process that has lost
         * the parking (a restart): long enough that such a process does not claim, enumerate
         * and park it again every few seconds, short enough that a charger found meanwhile is
         * not missed by much. A process that remembers the parking ignores this and serves the
         * node as soon as previews are allowed.
         */
        const val PREVIEW_DEFERRAL_MILLIS = 15L * 60L * 1_000L
        private const val BASE_RETRY_MILLIS = 30_000L
        private const val MAX_RETRY_MILLIS = 15L * 60L * 1_000L
        private const val MAX_RETRY_SHIFT = 5
        private val NEWEST_FIRST =
            compareByDescending(ProtonThumbnailQueueEntry::captureTimeEpochSeconds)
                .thenBy(ProtonThumbnailQueueEntry::nodeUid)
    }
}
