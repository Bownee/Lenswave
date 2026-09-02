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

        override fun readThumbnailQueue(userId: String): List<ProtonThumbnailQueueEntry> =
            entries[userId].orEmpty()

        override fun writeThumbnailQueue(userId: String, entries: List<ProtonThumbnailQueueEntry>) {
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
