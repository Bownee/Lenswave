package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.DriveEvent
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProtonEventSyncTest {
    private val clock = Clock()
    private val store = Store()
    private val source = Source()
    private val sync = ProtonEventSync(source, store, clock)

    @Test fun aNewlyDiscoveredSharedAlbumGetsACursorBeforeItsFirstContentListing() =
        runTest {
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            source.shared = setOf("shared")
            sync.withSnapshot(USER) {
                assertEquals("baseline", store.readEventState(USER).cursors["shared"])
                assertTrue(it.needsRefresh(ProtonSyncKeys.albumPhotos(LegacyNodeUid("shared", "album").value)))
            }
        }

    @Test fun snapshotRefreshUsesEventsAndDoesNotAcknowledgeAFailedListing() =
        runTest {
            val metadata =
                object : ProtonSyncMetadataStore {
                    override fun readLastSuccessfulSync(
                        userId: String,
                        source: String,
                    ): Long = 1L

                    override fun writeLastSuccessfulSync(
                        userId: String,
                        source: String,
                        timestampMillis: Long,
                    ) = Unit
                }
            val failures = mutableListOf<Throwable>()
            val snapshots =
                ProtonSnapshotSync(ProtonSnapshotCoordinator(metadata, clock), sync) { _, error ->
                    failures +=
                        error
                }
            var enumerations = 0
            var failListing = false
            var resets = 0

            suspend fun refresh() =
                snapshots.sync(
                    userId = USER,
                    syncKey = TIMELINE,
                    forceRemote = false,
                    hasSnapshot = true,
                    operation = "test",
                    publishFresh = {},
                    publishSyncing = {},
                    enumerate = {
                        enumerations++
                        if (failListing) throw IOException("listing failed")
                        "photos"
                    },
                    commit = { it },
                    publishResult = {},
                    publishCancelled = {},
                    publishFailed = {},
                    commitGate = { it() },
                    trustServerReset = { resets++ },
                )
            refresh()
            clock.now += 24 * 60 * 60_000L
            refresh()
            assertEquals(1, enumerations)
            clock.tick()
            source.pending =
                listOf(DriveEvent.NodeDeleted(DriveEventId("deleted"), LegacyNodeUid("volume", "photo"), null))
            failListing = true
            refresh()
            assertEquals(2, enumerations)
            assertEquals(1, failures.size)
            failListing = false
            refresh()
            assertEquals(3, enumerations)
            refresh()
            assertEquals(3, enumerations)
            clock.tick()
            source.pending = listOf(DriveEvent.ContinuityLost(DriveEventId("reset")))
            refresh()
            assertEquals(4, enumerations)
            assertEquals(1, resets)
        }

    @Test fun baselinePrecedesTheFirstListingAndCursorOnlyUpdatesDoNotInvalidateIt() =
        runTest {
            sync.withSnapshot(USER) { snapshot ->
                assertEquals("baseline", store.readEventState(USER).cursors["volume"])
                assertTrue(snapshot.needsRefresh(TIMELINE))
                snapshot.commit(TIMELINE)
            }
            clock.now += 24 * 60 * 60_000L
            source.pending = listOf(DriveEvent.CursorAdvanced(DriveEventId("new-cursor")))
            sync.withSnapshot(USER) { assertFalse(it.needsRefresh(TIMELINE)) }
            assertEquals("baseline", source.calls.last().cursor)
            assertEquals("new-cursor", store.readEventState(USER).cursors["volume"])
        }

    @Test fun changesRemainPendingForEveryUncommittedListingAfterRestart() =
        runTest {
            sync.withSnapshot(USER) {
                it.commit(TIMELINE)
                it.commit(ALBUMS)
            }
            clock.tick()
            source.pending = listOf(updated("one"), updated("two"))
            sync.withSnapshot(USER) { snapshot ->
                assertTrue(snapshot.needsRefresh(TIMELINE))
                assertTrue(snapshot.needsRefresh(ALBUMS))
                snapshot.commit(TIMELINE)
            }
            source.pending = emptyList()
            ProtonEventSync(source, store, clock).withSnapshot(USER) { snapshot ->
                assertFalse(snapshot.needsRefresh(TIMELINE))
                assertTrue(snapshot.needsRefresh(ALBUMS))
                snapshot.commit(ALBUMS)
            }
            assertEquals("two", source.calls.last().cursor)
        }

    @Test fun failedListingDoesNotAcknowledgeTheInvalidation() =
        runTest {
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            clock.tick()
            source.pending = listOf(updated("changed"))
            expectFailure<IOException> {
                sync.withSnapshot(USER) { throw IOException("listing failed") }
            }
            sync.withSnapshot(USER) { assertTrue(it.needsRefresh(TIMELINE)) }
        }

    @Test fun partiallyReadEventPagesAreReplayedAndFailuresBackOffAcrossCallers() =
        runTest {
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            clock.tick()
            source.pending = listOf(updated("partial"))
            source.failure = IOException("second event page failed")
            expectFailure<IOException> { sync.withSnapshot(USER) { error("must not refresh") } }
            val calls = source.calls.size
            assertEquals("baseline", store.readEventState(USER).cursors["volume"])
            expectFailure<IOException> { sync.withSnapshot(USER) { } }
            assertEquals(calls, source.calls.size)
            clock.tick()
            expectFailure<IOException> { sync.withSnapshot(USER) { } }
            assertEquals(calls + 1, source.calls.size)
            clock.tick()
            expectFailure<IOException> { sync.withSnapshot(USER) { } }
            assertEquals(calls + 1, source.calls.size)
            clock.tick()
            source.failure = null
            sync.withSnapshot(USER) {
                assertTrue(it.needsRefresh(TIMELINE))
                it.commit(TIMELINE)
            }
            assertEquals("baseline", source.calls.last().cursor)
            assertEquals("partial", store.readEventState(USER).cursors["volume"])
            assertEquals(15 * 60_000L, ProtonEventSync.retryDelayMillis(100))
        }

    @Test fun cancellationDoesNotAdvanceTheCursorOrStartFailureBackoff() =
        runTest {
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            clock.tick()
            source.pending = listOf(updated("partial"))
            source.failure = CancellationException("backgrounded")
            expectFailure<CancellationException> { sync.withSnapshot(USER) { } }
            assertEquals("baseline", store.readEventState(USER).cursors["volume"])
            source.failure = null
            sync.withSnapshot(USER) { assertTrue(it.needsRefresh(TIMELINE)) }
            assertEquals("partial", store.readEventState(USER).cursors["volume"])
        }

    @Test fun aFailedCursorWriteCannotErasePendingChanges() =
        runTest {
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            clock.tick()
            source.pending = listOf(updated("change"))
            store.failWrite = true
            expectFailure<IOException> { sync.withSnapshot(USER) { } }
            assertEquals("baseline", store.readEventState(USER).cursors["volume"])
            store.failWrite = false
            clock.tick()
            sync.withSnapshot(USER) { assertTrue(it.needsRefresh(TIMELINE)) }
        }

    @Test fun continuityLossPersistsTheResetUntilEachListingIsRebuilt() =
        runTest {
            sync.withSnapshot(USER) {
                it.commit(TIMELINE)
                it.commit(ALBUMS)
            }
            clock.tick()
            source.pending = listOf(DriveEvent.ContinuityLost(DriveEventId("reset")))
            sync.withSnapshot(USER) {
                assertTrue(it.requiresReset(TIMELINE))
                it.commit(TIMELINE)
            }
            source.pending = emptyList()
            ProtonEventSync(source, store, clock).withSnapshot(USER) {
                assertFalse(it.requiresReset(TIMELINE))
                assertTrue(it.requiresReset(ALBUMS))
            }
        }

    @Test fun lostSharedScopeStopsPollingAndCanBeRediscoveredAfterSharingChanges() =
        runTest {
            source.shared = setOf("shared")
            val albumKey = ProtonSyncKeys.albumPhotos(LegacyNodeUid("shared", "album").value)
            sync.withSnapshot(USER) { it.commit(albumKey) }
            clock.tick()
            source.byScope["shared"] = listOf(DriveEvent.ScopeAccessLost(DriveEventId("revoked")))
            sync.withSnapshot(USER) { assertTrue(it.hasLostAccess(albumKey)) }
            val sharedCalls = source.calls.count { it.scope == "shared" }
            clock.tick()
            sync.withSnapshot(USER) { }
            assertEquals(sharedCalls, source.calls.count { it.scope == "shared" })
            source.byScope["volume"] = listOf(DriveEvent.SharedWithMeUpdated(DriveEventId("sharing")))
            clock.tick()
            sync.withSnapshot(USER) { assertFalse(it.hasLostAccess(albumKey)) }
            source.byScope.clear()
            clock.tick()
            sync.withSnapshot(USER) { }
            assertEquals(sharedCalls + 1, source.calls.count { it.scope == "shared" })
        }

    @Test fun accountSwitchAndCacheClearDoNotReuseAnotherSessionCursor() =
        runTest {
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            sync.withSnapshot("other") { assertTrue(it.needsRefresh(TIMELINE)) }
            assertEquals(null, source.calls.last().cursor)
            store.states.remove(USER)
            sync.withSnapshot(USER) { assertTrue(it.needsRefresh(TIMELINE)) }
            assertEquals(null, source.calls.last().cursor)
        }

    @Test fun simultaneousRefreshesShareOnePollAndCannotAcknowledgeChangesDuringAnOlderListing() =
        runTest {
            val enumerating = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val first =
                async {
                    sync.withSnapshot(USER) {
                        enumerating.complete(Unit)
                        finish.await()
                        it.commit(TIMELINE)
                    }
                }
            enumerating.await()
            val second = async { sync.withSnapshot(USER) { assertFalse(it.needsRefresh(TIMELINE)) } }
            finish.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, source.calls.size)
        }

    @Test fun anEmptyLibraryAcquiresItsScopeWhenTheSdkCreatesThePhotoRoot() =
        runTest {
            source.root = null
            sync.withSnapshot(USER) { it.commit(TIMELINE) }
            assertTrue(source.calls.isEmpty())
            source.root = "volume"
            clock.tick()
            sync.withSnapshot(USER) { assertTrue(it.needsRefresh(TIMELINE)) }
            assertEquals("baseline", store.readEventState(USER).cursors["volume"])
        }

    private class Clock : LenswaveClock {
        var now = 1_000L

        override fun nowMillis(): Long = now

        fun tick() {
            now += ProtonEventSync.POLL_INTERVAL_MILLIS
        }
    }

    private class Store : ProtonEventStore {
        val states = mutableMapOf<String, ProtonEventState>()
        var failWrite = false

        override fun readEventState(userId: String): ProtonEventState = states[userId] ?: ProtonEventState()

        override fun writeEventState(
            userId: String,
            state: ProtonEventState,
        ) {
            if (failWrite) throw IOException("disk full")
            states[userId] = state
        }
    }

    private class Source : ProtonEventSource {
        data class Call(
            val scope: String,
            val cursor: String?,
        )

        val calls = mutableListOf<Call>()
        var root: String? = "volume"
        var shared = emptySet<String>()
        var pending = emptyList<DriveEvent>()
        val byScope = mutableMapOf<String, List<DriveEvent>>()
        var failure: Throwable? = null

        override suspend fun ownScope(userId: UserId): String? = root

        override fun albumScopes(userId: String): Set<String> = shared

        override fun events(
            userId: UserId,
            scope: ScopeId,
            cursor: DriveEventId?,
        ): Flow<DriveEvent> =
            flow {
                calls += Call(scope.id, cursor?.value)
                if (cursor == null) {
                    emit(DriveEvent.CursorAdvanced(DriveEventId("baseline")))
                } else {
                    (byScope[scope.id] ?: pending).forEach { emit(it) }
                }
                failure?.let { throw it }
            }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(operation: suspend () -> Unit) {
        try {
            operation()
            error("Expected ${T::class.java.simpleName}")
        } catch (
            error: Throwable,
        ) {
            if (error !is T) throw error
        }
    }

    private fun updated(id: String) =
        DriveEvent.NodeUpdated(DriveEventId(id), NodeUid("volume_photo"), null, false, false)

    companion object {
        private const val USER = "user"
        private const val TIMELINE = ProtonSyncKeys.TIMELINE
        private const val ALBUMS = ProtonSyncKeys.ALBUMS
    }
}
