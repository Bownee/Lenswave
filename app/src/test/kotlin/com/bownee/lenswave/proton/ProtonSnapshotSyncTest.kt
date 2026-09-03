package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSnapshotSyncTest {
    private val metadata = FakeMetadata()
    private val clock = FakeClock(10_000L)
    private val failures = mutableListOf<Pair<LenswaveOperation, Throwable>>()
    private val sync = ProtonSnapshotSync(ProtonSnapshotCoordinator(metadata, clock)) { operation, error ->
        failures += operation to error
    }
    private val events = mutableListOf<String>()

    @Test fun freshSnapshotOnlyPublishesTheCachedListing() = runTest {
        metadata.writeLastSuccessfulSync("user", KEY, clock.value)
        events.clear()

        sync.sync(hasSnapshot = true, enumerate = { fail("must not enumerate") })

        assertEquals(listOf("fresh"), events)
        assertEquals(clock.value, metadata.readLastSuccessfulSync("user", KEY))
        assertTrue(failures.isEmpty())
    }

    @Test fun staleSnapshotPublishesSyncingThenCommitsAndStampsBeforeTheResult() = runTest {
        metadata.writeLastSuccessfulSync("user", KEY, 1L)
        events.clear()
        clock.value = 1L + ProtonSyncSource.TIMELINE.maximumAgeMillis

        sync.sync(hasSnapshot = true, enumerate = { "remote" })

        assertEquals(listOf("syncing", "commit:remote", "stamp:${clock.value}", "result:remote"), events)
        assertTrue(failures.isEmpty())
    }

    @Test fun missingSnapshotAndForcedRefreshEnumerate() = runTest {
        sync.sync(hasSnapshot = false, enumerate = { "first" })
        events.clear()
        sync.sync(hasSnapshot = true, forceRemote = true, enumerate = { "second" })

        assertEquals(listOf("syncing", "commit:second", "stamp:10000", "result:second"), events)
    }

    @Test fun failurePublishesRefreshFailedWithoutStampingTheSync() = runTest {
        val error = IllegalStateException("boom")

        sync.sync(hasSnapshot = false, enumerate = { throw error })

        assertEquals(listOf("syncing", "failed"), events)
        assertEquals(listOf(LenswaveOperation.TIMELINE_SYNC to error), failures)
        assertEquals(0L, metadata.readLastSuccessfulSync("user", KEY))
    }

    @Test fun commitFailureIsReportedLikeAnEnumerationFailure() = runTest {
        sync.sync(
            hasSnapshot = false,
            enumerate = { "remote" },
            commit = { throw IllegalArgumentException("disk full") },
        )

        assertEquals(listOf("syncing", "failed"), events)
        assertEquals(1, failures.size)
        assertEquals(0L, metadata.readLastSuccessfulSync("user", KEY))
    }

    @Test fun cancellationClearsSyncingAndRethrows() = runTest {
        val cancellation = CancellationException("account changed")
        var rethrown: Throwable? = null

        try {
            sync.sync(hasSnapshot = false, enumerate = { throw cancellation })
        } catch (error: CancellationException) {
            rethrown = error
        }

        assertEquals(cancellation, rethrown)
        assertEquals(listOf("syncing", "cancelled"), events)
        assertTrue(failures.isEmpty())
        assertEquals(0L, metadata.readLastSuccessfulSync("user", KEY))
    }

    private suspend fun ProtonSnapshotSync.sync(
        hasSnapshot: Boolean,
        forceRemote: Boolean = false,
        enumerate: suspend () -> String,
        commit: (String) -> Unit = { events += "commit:$it" },
    ) = sync(
        userId = "user",
        source = ProtonSyncSource.TIMELINE,
        syncKey = KEY,
        forceRemote = forceRemote,
        hasSnapshot = hasSnapshot,
        operation = LenswaveOperation.TIMELINE_SYNC,
        publishFresh = { events += "fresh" },
        publishSyncing = { events += "syncing" },
        enumerate = enumerate,
        commit = commit,
        publishResult = { events += "result:$it" },
        publishCancelled = { events += "cancelled" },
        publishFailed = { events += "failed" },
    )

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private inner class FakeMetadata : ProtonSyncMetadataStore {
        private val values = mutableMapOf<Pair<String, String>, Long>()
        override fun readLastSuccessfulSync(userId: String, source: String): Long =
            values[userId to source] ?: 0L

        override fun writeLastSuccessfulSync(userId: String, source: String, timestampMillis: Long) {
            values[userId to source] = timestampMillis
            events += "stamp:$timestampMillis"
        }
    }

    private class FakeClock(var value: Long) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private companion object {
        const val KEY = "timeline"
    }
}
