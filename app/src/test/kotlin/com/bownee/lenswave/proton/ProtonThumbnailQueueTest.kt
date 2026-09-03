package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonThumbnailQueueTest {
    @Test
    fun newestCaptureTimeIsAlwaysChosenFirst() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(
            USER_ID,
            "timeline",
            listOf(candidate("old", 100), candidate("newest", 300), candidate("middle", 200)),
        )

        assertEquals(
            listOf("newest", "middle", "old"),
            queue.claimReady(USER_ID, limit = 3).map(ProtonThumbnailQueueEntry::nodeUid),
        )
    }

    @Test
    fun sourceReplacementReordersExistingEntriesByCaptureTime() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(
            USER_ID,
            "timeline",
            listOf(candidate("previously-newest", 300), candidate("updated", 100)),
        )

        queue.replaceSource(
            USER_ID,
            "timeline",
            listOf(candidate("previously-newest", 300), candidate("updated", 400)),
        )

        assertEquals("updated", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun replacingOneSourcePreservesItemsReferencedByAnotherSource() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())

        queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))
        queue.replaceSource(USER_ID, "album", candidates("b", "c"))
        queue.replaceSource(USER_ID, "timeline", candidates("b"))

        assertEquals(setOf("b", "c"), store.entries.getValue(USER_ID).map { it.nodeUid }.toSet())
        assertEquals(setOf("timeline", "album"), entry(store, "b").sources)
    }

    @Test
    fun removingNewerSourceRestoresRemainingSourcesCaptureTime() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf(candidate("shared", 100), candidate("other", 200)))
        queue.replaceSource(USER_ID, "album", listOf(candidate("shared", 300)))
        val initiallyClaimed = queue.claimReady(USER_ID, limit = 1)
        assertEquals("shared", initiallyClaimed.single().nodeUid)
        queue.release(USER_ID, initiallyClaimed.map(ProtonThumbnailQueueEntry::nodeUid))

        queue.replaceSource(USER_ID, "album", emptyList())

        assertEquals("other", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun aFailedItemIsDeferredWithoutBlockingTheNextNewestThumbnail() = runBlocking {
        val clock = FakeClock()
        val queue = ProtonThumbnailQueue(FakeStore(), clock)
        queue.replaceSource(
            USER_ID,
            "timeline",
            listOf(candidate("newest", 200), candidate("older", 100)),
        )

        assertEquals("newest", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        queue.settle(USER_ID, emptySet(), setOf("newest"))
        assertEquals("older", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        queue.settle(USER_ID, setOf("older"), emptySet())
        assertEquals(emptyList<ProtonThumbnailQueueEntry>(), queue.claimReady(USER_ID, limit = 1))

        clock.value += 30_000L
        assertEquals("newest", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun invalidatedThumbnailIsRestoredForImmediateDownloadAtItsCaptureTime() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf(candidate("photo", 200)))
        queue.settle(USER_ID, setOf("photo"), emptySet())

        queue.retryNow(USER_ID, candidate("photo", 200), setOf("timeline", "album:one"))

        val restored = queue.claimReady(USER_ID, limit = 1).single()
        assertEquals("photo", restored.nodeUid)
        assertEquals(200L, restored.captureTimeEpochSeconds)
        assertEquals(setOf("timeline", "album:one"), restored.sources)
    }

    @Test
    fun claimedBatchesDoNotDuplicateInFlightWork() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", candidates("a", "b", "c"))

        val first = queue.claimReady(USER_ID, limit = 2)
        val second = queue.claimReady(USER_ID, limit = 2)

        assertEquals(2, first.size)
        assertEquals(1, second.size)
        assertEquals(setOf("a", "b", "c"), (first + second).map { it.nodeUid }.toSet())
    }

    @Test
    fun releasedClaimsCanBeSelectedAgain() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", candidates("photo"))
        val claimed = queue.claimReady(USER_ID, limit = 1)

        queue.release(USER_ID, claimed.map { it.nodeUid })

        assertEquals("photo", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun settlingABatchPersistsSuccessAndFailureTogether() = runBlocking {
        val store = FakeStore()
        val clock = FakeClock()
        val queue = ProtonThumbnailQueue(store, clock)
        queue.replaceSource(USER_ID, "timeline", candidates("success", "failure"))
        queue.claimReady(USER_ID, limit = 2)
        val writesBeforeSettlement = store.writeCount

        val completed = queue.settle(
            USER_ID,
            successfulNodeUids = setOf("success"),
            failedNodeUids = setOf("failure"),
        )

        assertEquals(listOf("success"), completed.map { it.nodeUid })
        assertEquals(writesBeforeSettlement + 1, store.writeCount)
        assertEquals(emptyList<ProtonThumbnailQueueEntry>(), queue.claimReady(USER_ID, limit = 1))
        clock.value += 30_000L
        assertEquals("failure", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun settlingUsesSourcesAddedWhileAThumbnailWasInFlight() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", candidates("photo"))
        queue.claimReady(USER_ID, limit = 1)
        queue.replaceSource(USER_ID, "album:one", candidates("photo"))

        val completed = queue.settle(USER_ID, setOf("photo"), emptySet())

        assertEquals(setOf("timeline", "album:one"), completed.single().sources)
    }

    @Test
    fun pendingQueueSurvivesCreatingANewQueueInstance() = runBlocking {
        val store = FakeStore()
        ProtonThumbnailQueue(store, FakeClock()).replaceSource(USER_ID, "timeline", candidates("photo"))

        val restored = ProtonThumbnailQueue(store, FakeClock())

        assertEquals("photo", restored.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun pendingCountIncludesClaimedAndDelayedWorkUntilItSettles() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", candidates("claimed", "delayed"))
        queue.claimReady(USER_ID, limit = 1)
        queue.settle(USER_ID, emptySet(), setOf("delayed"))

        assertEquals(2, queue.pendingCount(USER_ID))

        queue.settle(USER_ID, setOf("claimed"), emptySet())
        assertEquals(1, queue.pendingCount(USER_ID))
    }

    @Test
    fun deletedAlbumsNoLongerKeepTheirThumbnailWork() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())
        queue.replaceSource(USER_ID, "album:kept", candidates("kept-photo"))
        queue.replaceSource(USER_ID, "album:deleted", candidates("deleted-photo"))

        queue.replaceSources(USER_ID, emptyMap(), retainedAlbumNodeUids = listOf("kept"))

        assertEquals(listOf("kept-photo"), store.entries.getValue(USER_ID).map { it.nodeUid })
    }

    @Test
    fun replacingAlbumCoversPreservesTimelineWork() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())
        queue.replaceSource(USER_ID, "timeline", candidates("timeline-photo"))
        queue.replaceSource(USER_ID, "album-covers", candidates("old-cover"))

        queue.replaceSources(
            USER_ID,
            mapOf("album-covers" to candidates("new-cover")),
            retainedAlbumNodeUids = emptyList(),
        )

        assertEquals(
            setOf("timeline-photo", "new-cover"),
            store.entries.getValue(USER_ID).map { it.nodeUid }.toSet(),
        )
    }

    private fun candidate(nodeUid: String, captureTimeEpochSeconds: Long) =
        ProtonThumbnailCandidate(nodeUid, captureTimeEpochSeconds)

    private fun candidates(vararg nodeUids: String): List<ProtonThumbnailCandidate> =
        nodeUids.mapIndexed { index, nodeUid -> candidate(nodeUid, nodeUids.size - index.toLong()) }

    private fun entry(store: FakeStore, nodeUid: String) =
        store.entries.getValue(USER_ID).single { it.nodeUid == nodeUid }

    private class FakeStore : ProtonThumbnailQueueStore {
        val entries = mutableMapOf<String, List<ProtonThumbnailQueueEntry>>()
        var writeCount = 0

        override fun readThumbnailQueue(userId: String): List<ProtonThumbnailQueueEntry> =
            entries[userId].orEmpty()

        override fun writeThumbnailQueue(userId: String, entries: List<ProtonThumbnailQueueEntry>) {
            writeCount++
            this.entries[userId] = entries.toList()
        }
    }

    private class FakeClock(var value: Long = 1_000L) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private companion object {
        const val USER_ID = "user"
    }
}
