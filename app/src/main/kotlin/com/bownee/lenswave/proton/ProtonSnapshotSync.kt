package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Runs one authoritative snapshot refresh: decides whether the cached listing is fresh enough,
 * publishes the syncing state, enumerates from Proton, commits the result to the cache, stamps the
 * successful sync, and publishes the outcome. The listing-specific parts arrive as callbacks so the
 * timeline, tag, album, and album-photo repositories share exactly one control flow.
 */
internal class ProtonSnapshotSync internal constructor(
    private val snapshots: ProtonSnapshotCoordinator,
    private val reportFailure: (LenswaveOperation, Throwable) -> Unit,
) {
    @Inject constructor(snapshots: ProtonSnapshotCoordinator) :
        this(snapshots, LenswaveDiagnostics::reportFailure)

    /**
     * Callers read the cached snapshot (and [hasSnapshot]) before calling so those reads stay
     * outside the failure handling, exactly as the repositories did before this helper existed.
     *
     * - Fresh snapshot: only [publishFresh] runs.
     * - Otherwise: [publishSyncing], then [enumerate], [commit], the sync timestamp is written,
     *   then [publishResult].
     * - Cancellation anywhere after the freshness check: [publishCancelled], then rethrow.
     * - Any other failure: it is reported under [operation], then [publishFailed].
     */
    suspend fun <T> sync(
        userId: String,
        source: ProtonSyncSource,
        syncKey: String,
        forceRemote: Boolean,
        hasSnapshot: Boolean,
        operation: LenswaveOperation,
        publishFresh: () -> Unit,
        publishSyncing: () -> Unit,
        enumerate: suspend () -> T,
        commit: (T) -> Unit,
        publishResult: (T) -> Unit,
        publishCancelled: () -> Unit,
        publishFailed: () -> Unit,
    ) {
        try {
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId,
                source,
                syncKey,
                forceRemote,
                hasSnapshot,
            )
            if (!shouldEnumerate) {
                publishFresh()
                return
            }
            publishSyncing()
            val result = enumerate()
            commit(result)
            snapshots.commit(userId, syncKey)
            publishResult(result)
        } catch (error: CancellationException) {
            publishCancelled()
            throw error
        } catch (error: Throwable) {
            reportFailure(operation, error)
            publishFailed()
        }
    }
}
