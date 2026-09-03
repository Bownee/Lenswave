package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtonThumbnailQueueTest {
    @Test
    fun replacingOneSourcePreservesItemsReferencedByAnotherSource() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())

        queue.replaceSource(USER_ID, "timeline", listOf("a", "b"))
        queue.replaceSource(USER_ID, "album", listOf("b", "c"))
        queue.replaceSource(USER_ID, "timeline", listOf("b"))

        assertEquals(setOf("b", "c"), store.entries.getValue(USER_ID).map { it.nodeUid }.toSet())
        assertEquals(setOf("timeline", "album"), entry(store, "b").sources)
    }

    @Test
    fun visibleItemsAreChosenBeforeTheRestOfTheCurrentSection() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("background", "section", "visible"))

        queue.prioritizeSection(USER_ID, listOf("section", "visible"))
        queue.prioritizeVisible(USER_ID, listOf("visible"))

        assertEquals("visible", queue.nextReady(USER_ID)?.nodeUid)
        queue.complete(USER_ID, "visible")
        assertEquals("section", queue.nextReady(USER_ID)?.nodeUid)
    }

    @Test
    fun metadataDiscoveredAfterScrollingInheritsVisiblePriority() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.prioritizeSection(USER_ID, listOf("visible"))
        queue.prioritizeVisible(USER_ID, listOf("visible"))

        queue.replaceSource(USER_ID, "timeline", listOf("background", "visible"))

        assertEquals("visible", queue.nextReady(USER_ID)?.nodeUid)
    }

    @Test
    fun aFailedItemIsDeferredWithoutBlockingAnotherThumbnail() = runBlocking {
        val clock = FakeClock()
        val queue = ProtonThumbnailQueue(FakeStore(), clock)
        queue.replaceSource(USER_ID, "timeline", listOf("failed", "other"))

        assertEquals("failed", queue.nextReady(USER_ID)?.nodeUid)
        queue.defer(USER_ID, "failed")
        assertEquals("other", queue.nextReady(USER_ID)?.nodeUid)
        queue.complete(USER_ID, "other")
        assertNull(queue.nextReady(USER_ID))

        clock.value += 30_000L
        assertEquals("failed", queue.nextReady(USER_ID)?.nodeUid)
    }

    @Test
    fun newlyVisibleItemBypassesAnOldRetryDelay() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("photo"))
        queue.defer(USER_ID, "photo")
        assertNull(queue.nextReady(USER_ID))

        queue.prioritizeVisible(USER_ID, setOf("photo"))

        assertEquals("photo", queue.claimVisible(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun unchangedVisibleItemsKeepARecentlyEarnedRetryDelay() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("photo"))
        queue.prioritizeVisible(USER_ID, setOf("photo"))
        queue.claimVisible(USER_ID, limit = 1)
        queue.settle(USER_ID, emptySet(), setOf("photo"))

        queue.prioritizeVisible(USER_ID, setOf("photo"))

        assertNull(queue.nextReady(USER_ID))
    }

    @Test
    fun invalidatedThumbnailIsRestoredForImmediateDownload() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("photo"))
        queue.complete(USER_ID, "photo")

        queue.retryNow(USER_ID, "photo", setOf("timeline", "album:one"))

        val restored = queue.claimVisible(USER_ID, limit = 1).single()
        assertEquals("photo", restored.nodeUid)
        assertEquals(setOf("timeline", "album:one"), restored.sources)
    }

    @Test
    fun claimedBatchesDoNotDuplicateInFlightWork() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("a", "b", "c"))

        val first = queue.claimReady(USER_ID, limit = 2)
        val second = queue.claimReady(USER_ID, limit = 2)

        assertEquals(2, first.size)
        assertEquals(1, second.size)
        assertEquals(setOf("a", "b", "c"), (first + second).map { it.nodeUid }.toSet())
    }

    @Test
    fun visibleClaimsDoNotConsumeBackgroundWork() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("background", "visible"))
        queue.prioritizeVisible(USER_ID, setOf("visible"))

        val claimed = queue.claimVisible(USER_ID, limit = 10)

        assertEquals(listOf("visible"), claimed.map { it.nodeUid })
    }

    @Test
    fun releasedClaimsCanBeSelectedAgain() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("photo"))
        val claimed = queue.claimReady(USER_ID, limit = 1)

        queue.release(USER_ID, claimed.map { it.nodeUid })

        assertEquals("photo", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
    }

    @Test
    fun settlingABatchPersistsSuccessAndFailureTogether() = runBlocking {
        val store = FakeStore()
        val clock = FakeClock()
        val queue = ProtonThumbnailQueue(store, clock)
        queue.replaceSource(USER_ID, "timeline", listOf("success", "failure"))
        queue.claimReady(USER_ID, limit = 2)
        val writesBeforeSettlement = store.writeCount

        val completed = queue.settle(
            USER_ID,
            successfulNodeUids = setOf("success"),
            failedNodeUids = setOf("failure"),
        )

        assertEquals(listOf("success"), completed.map { it.nodeUid })
        assertEquals(writesBeforeSettlement + 1, store.writeCount)
        assertNull(queue.nextReady(USER_ID))
        clock.value += 30_000L
        assertEquals("failure", queue.nextReady(USER_ID)?.nodeUid)
    }

    @Test
    fun settlingUsesSourcesAddedWhileAThumbnailWasInFlight() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("photo"))
        queue.claimReady(USER_ID, limit = 1)
        queue.replaceSource(USER_ID, "album:one", listOf("photo"))

        val completed = queue.settle(USER_ID, setOf("photo"), emptySet())

        assertEquals(setOf("timeline", "album:one"), completed.single().sources)
    }

    @Test
    fun pendingQueueSurvivesCreatingANewQueueInstance() = runBlocking {
        val store = FakeStore()
        ProtonThumbnailQueue(store, FakeClock()).replaceSource(USER_ID, "trash", listOf("photo"))

        val restored = ProtonThumbnailQueue(store, FakeClock())

        assertEquals("photo", restored.nextReady(USER_ID)?.nodeUid)
    }

    @Test
    fun completedWorkIsPersistedWhenTheWorkerFlushes() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("photo"))
        queue.complete(USER_ID, "photo")

        queue.flush(USER_ID)

        assertNull(ProtonThumbnailQueue(store, FakeClock()).nextReady(USER_ID))
    }

    @Test
    fun pendingCountIncludesClaimedAndDelayedWorkUntilItSettles() = runBlocking {
        val queue = ProtonThumbnailQueue(FakeStore(), FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("claimed", "delayed"))
        queue.claimReady(USER_ID, limit = 1)
        queue.defer(USER_ID, "delayed")

        assertEquals(2, queue.pendingCount(USER_ID))

        queue.settle(USER_ID, setOf("claimed"), emptySet())
        assertEquals(1, queue.pendingCount(USER_ID))
    }

    @Test
    fun deletedAlbumsNoLongerKeepTheirThumbnailWork() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())
        queue.replaceSource(USER_ID, "album:kept", listOf("kept-photo"))
        queue.replaceSource(USER_ID, "album:deleted", listOf("deleted-photo"))

        queue.retainAlbumSources(USER_ID, listOf("kept"))

        assertEquals(listOf("kept-photo"), store.entries.getValue(USER_ID).map { it.nodeUid })
    }

    @Test
    fun replacingAlbumCoversPreservesTimelineAndTrashWork() = runBlocking {
        val store = FakeStore()
        val queue = ProtonThumbnailQueue(store, FakeClock())
        queue.replaceSource(USER_ID, "timeline", listOf("timeline-photo"))
        queue.replaceSource(USER_ID, "trash", listOf("trash-photo"))
        queue.replaceSource(USER_ID, "album-covers", listOf("old-cover"))

        queue.replaceSources(
            USER_ID,
            mapOf("album-covers" to listOf("new-cover")),
            retainedAlbumNodeUids = emptyList(),
        )

        assertEquals(
            setOf("timeline-photo", "trash-photo", "new-cover"),
            store.entries.getValue(USER_ID).map { it.nodeUid }.toSet(),
        )
    }

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
