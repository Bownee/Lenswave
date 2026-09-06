package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Runs one authoritative snapshot refresh: checks Drive events and the cached listing's commit,
 * publishes the syncing state, enumerates from Proton, commits the result to the cache, stamps the
 * successful sync, and publishes the outcome. The listing-specific parts arrive as callbacks so the
 * timeline, tag, album, and album-photo repositories share exactly one control flow.
 */
internal class ProtonSnapshotSync internal constructor(
    private val snapshots: ProtonSnapshotCoordinator,
    private val events: ProtonEventSync? = null,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    @Inject
    constructor(snapshots: ProtonSnapshotCoordinator, events: ProtonEventSync) :
        this(snapshots, events, { operation, error -> LenswaveDiagnostics.reportFailure(operation, error) })

    /**
     * Callers read the cached snapshot (and [hasSnapshot]) before calling so those reads stay
     * outside the failure handling, exactly as the repositories did before this helper existed.
     *
     * - Fresh snapshot: only [publishFresh] runs.
     * - Otherwise: [publishSyncing], then [enumerate], [commit], the sync timestamp is written,
     *   then [publishResult] with what [commit] returned (usually the enumerated value; a commit
     *   may narrow it). Those three run inside [commitGate], so a repository holds the lock
     *   that serializes them against its other mutations without holding it across the
     *   enumeration.
     * - Cancellation anywhere after the freshness check: [publishCancelled], then rethrow.
     * - Any other failure: it is reported under [operation], then [publishFailed] with it, so a
     *   repository can tell a refused listing (see [ProtonSuspiciousListingException]) from a
     *   refresh that merely failed.
     */
    suspend fun <T> sync(
        userId: String,
        syncKey: String,
        forceRemote: Boolean,
        hasSnapshot: Boolean,
        operation: String,
        publishFresh: () -> Unit,
        publishSyncing: () -> Unit,
        enumerate: suspend () -> T,
        commit: (T) -> T,
        publishResult: (T) -> Unit,
        publishCancelled: () -> Unit,
        publishFailed: (error: Throwable) -> Unit,
        commitGate: suspend (commit: suspend () -> Unit) -> Unit,
        inaccessibleSnapshot: (() -> T)? = null,
        trustServerReset: () -> Unit = {},
    ) {
        try {
            val refresh: suspend (ProtonEventSync.Snapshot?) -> Unit = { eventSnapshot ->
                val shouldEnumerate =
                    snapshots.shouldEnumerate(
                        userId,
                        syncKey,
                        forceRemote,
                        hasSnapshot,
                    )
                if (!shouldEnumerate && eventSnapshot?.needsRefresh(syncKey) != true) {
                    publishFresh()
                } else {
                    publishSyncing()
                    if (eventSnapshot?.requiresReset(syncKey) == true ||
                        eventSnapshot?.hasLostAccess(syncKey) == true
                    ) {
                        trustServerReset()
                    }
                    val result =
                        if (eventSnapshot?.hasLostAccess(syncKey) == true && inaccessibleSnapshot != null) {
                            inaccessibleSnapshot()
                        } else {
                            enumerate()
                        }
                    commitGate {
                        val committed = commit(result)
                        snapshots.commit(userId, syncKey)
                        eventSnapshot?.commit(syncKey)
                        publishResult(committed)
                    }
                }
            }
            if (events == null) refresh(null) else events.withSnapshot(userId) { refresh(it) }
        } catch (error: CancellationException) {
            publishCancelled()
            throw error
        } catch (error: Throwable) {
            reportFailure(operation, error)
            publishFailed(error)
        }
    }
}
