package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.DriveEvent
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.ScopeId
import javax.inject.Inject
import javax.inject.Singleton

/** Cursors and outstanding snapshot invalidations are committed in the same encrypted record. */
internal data class ProtonEventState(
    val ownScope: String? = null,
    val generation: Long = 1L,
    val resetGeneration: Long = 0L,
    val cursors: Map<String, String> = emptyMap(),
    val snapshots: Map<String, Long> = emptyMap(),
    val lostScopes: Set<String> = emptySet(),
)

internal interface ProtonEventStore {
    fun readEventState(userId: String): ProtonEventState

    fun writeEventState(
        userId: String,
        state: ProtonEventState,
    )
}

internal interface ProtonEventSource {
    suspend fun ownScope(userId: UserId): String?

    fun albumScopes(userId: String): Set<String>

    fun events(
        userId: UserId,
        scope: ScopeId,
        cursor: DriveEventId?,
    ): Flow<DriveEvent>
}

/**
 * Serializes event checks with snapshot commits. Events durably invalidate the cached listings;
 * only listings subsequently requested by the UI are fetched. A cursor cannot hide an unfinished
 * refresh after a crash: its generation remains newer than that listing's committed generation.
 * Initial cursors are acquired before enumeration, including when upgrading an older cache.
 */
@Singleton
internal class ProtonEventSync
    @Inject
    constructor(
        private val source: ProtonEventSource,
        private val store: ProtonEventStore,
        private val clock: LenswaveClock,
    ) {
        private val mutex = Mutex()
        private val checks = mutableMapOf<String, Check>()

        suspend fun forget(userId: String) =
            mutex.withLock {
                checks.remove(userId)
                Unit
            }

        suspend fun <T> withSnapshot(
            userId: String,
            operation: suspend (Snapshot) -> T,
        ): T =
            mutex.withLock {
                val state = poll(userId)
                operation(Snapshot(userId, state))
            }

        inner class Snapshot internal constructor(
            private val userId: String,
            private val state: ProtonEventState,
        ) {
            fun needsRefresh(key: String): Boolean = state.snapshots[key] != state.generation

            fun requiresReset(key: String): Boolean = (state.snapshots[key] ?: 0L) < state.resetGeneration

            fun hasLostAccess(key: String): Boolean =
                if (key.startsWith("${ProtonSyncKeys.ALBUM_PHOTOS}:")) {
                    val uid = key.removePrefix("${ProtonSyncKeys.ALBUM_PHOTOS}:")
                    protonEventScope(uid) in state.lostScopes
                } else {
                    key != ProtonSyncKeys.ALBUMS && state.ownScope != null && state.ownScope in state.lostScopes
                }

            fun commit(key: String) {
                // Read the last commit too: a snapshot may be followed by another under this check.
                val latest = store.readEventState(userId)
                store.writeEventState(userId, latest.copy(snapshots = latest.snapshots + (key to state.generation)))
            }
        }

        private suspend fun poll(userId: String): ProtonEventState {
            var state = store.readEventState(userId)
            val now = clock.nowMillis()
            val previous = checks[userId]
            val albumScopes = source.albumScopes(userId)
            val untrackedScope = albumScopes.any { it !in state.cursors && it !in state.lostScopes }
            if (previous != null && now >= previous.at && now - previous.at < previous.delayMillis) {
                previous.failure?.let { throw it }
                // Missing state means this user disconnected and reconnected; acquire a new baseline.
                // Newly discovered shared albums need a baseline before their first content listing.
                if (state.ownScope != null && !untrackedScope &&
                    (state.cursors.isNotEmpty() || state.snapshots.isNotEmpty())
                ) {
                    return state
                }
            }
            try {
                val ownScope = state.ownScope ?: source.ownScope(UserId(userId))
                val scopes = albumScopes + listOfNotNull(ownScope)
                var changed = state.ownScope != ownScope
                var reset = false
                var cursors = state.cursors
                var lostScopes = state.lostScopes
                for (scope in scopes - lostScopes) {
                    val cursor = cursors[scope]
                    // A newly discovered scope may have changed since an older listing was written.
                    if (cursor == null) changed = true
                    source.events(UserId(userId), ScopeId(scope), cursor?.let(::DriveEventId)).collect { event ->
                        if (event !is DriveEvent.CursorAdvanced) changed = true
                        if (event is DriveEvent.ContinuityLost) reset = true
                        if (event is DriveEvent.ScopeAccessLost) lostScopes = lostScopes + scope
                        if (event is DriveEvent.SharedWithMeUpdated) {
                            lostScopes =
                                lostScopes.intersect(setOfNotNull(ownScope))
                        }
                        cursors = cursors + (scope to event.id.value)
                    }
                }
                val next =
                    state.copy(
                        ownScope = ownScope,
                        generation = if (changed) Math.addExact(state.generation, 1L) else state.generation,
                        resetGeneration = if (reset) Math.addExact(state.generation, 1L) else state.resetGeneration,
                        cursors = cursors,
                        lostScopes = lostScopes,
                    )
                if (next != state) store.writeEventState(userId, next)
                state = next
                checks[userId] = Check(now, POLL_INTERVAL_MILLIS)
                return state
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failures = (previous?.failures ?: 0) + 1
                checks[userId] = Check(now, retryDelayMillis(failures), failures, error)
                throw error
            }
        }

        private data class Check(
            val at: Long,
            val delayMillis: Long,
            val failures: Int = 0,
            val failure: Throwable? = null,
        )

        companion object {
            const val POLL_INTERVAL_MILLIS = 60_000L

            internal fun retryDelayMillis(failures: Int): Long =
                (POLL_INTERVAL_MILLIS * (1L shl (failures - 1).coerceIn(0, 4))).coerceAtMost(15 * 60_000L)
        }
    }
