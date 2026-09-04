package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonRenditionSyncTest {
    private val clock = FakeClock()
    private val store = FakeStore()
    private val flushScope = TestScope().backgroundScope
    private val thumbnails = ProtonThumbnailQueue(store, clock, ProtonQueueName.THUMBNAILS, flushScope)
    private val previews = ProtonThumbnailQueue(store, clock, ProtonQueueName.PREVIEWS, flushScope)
    private val source = FakeSource()
    private val availability = FakeAvailability()
    private val sync = ProtonRenditionSync(source, availability, thumbnails, previews, clock)

    @Test
    fun `previews reported only in the final result still leave the queue`() =
        runBlocking {
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a", "b"))
            source.previewProgress = listOf(ThumbnailBatchResult(setOf("a"), emptyMap()))
            source.previewResult = ThumbnailBatchResult(setOf("a", "b"), emptyMap())

            val step = sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(ProtonThumbnailQueueStep.Processed, step)
            assertEquals(0, previews.pendingCount(USER.id))
            assertEquals(setOf("a", "b"), availability.previewsAvailable.flatten().toSet())
        }

    @Test
    fun `a processed batch leaves the write to the debounce`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailResult = ThumbnailBatchResult(setOf("a", "b"), emptyMap())
            val writesBefore = store.writeCount

            sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(writesBefore, store.writeCount)
            assertEquals(0, thumbnails.pendingCount(USER.id))
        }

    @Test
    fun `every Nth batch and the idle step force a write`() =
        runBlocking {
            val writesBefore = store.writeCount

            // One entry per batch; retryNow marks the queue changed without writing it.
            suspend fun processOne(index: Int) {
                val nodeUid = "photo-$index"
                thumbnails.retryNow(
                    USER.id,
                    ProtonThumbnailCandidate(nodeUid, index.toLong()),
                    setOf(ProtonSyncKeys.QueueSource.TIMELINE),
                )
                source.thumbnailResult = ThumbnailBatchResult(setOf(nodeUid), emptyMap())
                assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = true) {})
            }

            repeat(ProtonQueueFlushPolicy.BATCHES_PER_FORCED_FLUSH - 1) { index -> processOne(index) }
            assertEquals(writesBefore, store.writeCount)
            processOne(ProtonQueueFlushPolicy.BATCHES_PER_FORCED_FLUSH)
            assertEquals(writesBefore + 1, store.writeCount)

            processOne(ProtonQueueFlushPolicy.BATCHES_PER_FORCED_FLUSH + 1)
            assertEquals(writesBefore + 1, store.writeCount)
            val idle = sync.downloadNextBatch(USER, allowPreviews = true) {}
            assertEquals(ProtonThumbnailQueueStep.Idle(hasPending = false), idle)
            assertEquals(writesBefore + 2, store.writeCount)
        }

    @Test
    fun `a stopped run writes the queues and releases its claims`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailProgress = listOf(ThumbnailBatchResult(setOf("a"), emptyMap()))
            source.thumbnailFailure = kotlinx.coroutines.CancellationException("stopped")
            val writesBefore = store.writeCount

            val stopped = runCatching { sync.downloadNextBatch(USER, allowPreviews = true) {} }

            assertTrue(stopped.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(writesBefore + 1, store.writeCount)
            assertEquals(listOf("b"), store.readQueue(USER.id, ProtonQueueName.THUMBNAILS).map { it.nodeUid })
            assertEquals(listOf("b"), thumbnails.claimReady(USER.id, limit = 2).map { it.nodeUid })
        }

    @Test
    fun `claims a batch left behind are cleared instead of idling forever`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a"))
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("p"))
            // Claimed by a batch that was never settled nor released.
            thumbnails.claimReady(USER.id, limit = 1)
            previews.claimReady(USER.id, limit = 1)
            source.thumbnailResult = ThumbnailBatchResult(setOf("a"), emptyMap())

            val idle = sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = 0L), idle)
            assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = true) {})
            assertEquals(0, thumbnails.pendingCount(USER.id))
            assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = true) {})
            assertEquals(1, source.previewCalls)
        }

    @Test
    fun `thumbnails come before previews and settle by their sources`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("x"))
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.ALBUM_COVERS, candidates("x", "cover"))
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("p"))
            source.thumbnailResult = ThumbnailBatchResult(setOf("x", "cover", "orphan"), emptyMap())

            sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(0, thumbnails.pendingCount(USER.id))
            assertEquals(1, previews.pendingCount(USER.id))
            assertEquals(listOf("orphan"), source.removedThumbnails)
            val published = availability.thumbnailsAvailable.single()
            assertEquals(setOf("x"), published.timeline)
            assertEquals(setOf("x", "cover"), published.albumCovers)
            assertTrue(published.albumPhotos.isEmpty())
        }

    @Test
    fun `previews wait for the charger without being claimed`() =
        runBlocking {
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a"))

            val step = sync.downloadNextBatch(USER, allowPreviews = false) {}

            val idle = step as ProtonThumbnailQueueStep.Idle
            assertFalse(idle.hasPending)
            assertTrue(idle.previewsDeferred)
            assertEquals(0, source.previewCalls)
            assertEquals(1, previews.pendingCount(USER.id))
        }

    @Test
    fun `a download that throws backs the whole batch off`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailFailure = IllegalStateException("boom")

            val step = sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(ProtonThumbnailQueueStep.Processed, step)
            assertEquals(2, thumbnails.pendingCount(USER.id))
            assertTrue(thumbnails.claimReady(USER.id, limit = 2).isEmpty())
            clock.value += 30_000L
            assertEquals(2, thumbnails.claimReady(USER.id, limit = 2).size)
        }

    @Test
    fun `progress counts stored and pending renditions of both queues`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a"))
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a", "b"))
            source.storedThumbnails = 10
            source.storedPreviews = 7

            assertEquals(
                ProtonThumbnailWorkProgress(stored = 10, pending = 1, previewsStored = 7, previewsPending = 2),
                sync.progress(USER),
            )
        }

    @Test
    fun `thumbnail marks are coalesced within the publish interval and flushed at batch end`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b", "c"))
            source.thumbnailProgress =
                listOf("a", "b", "c").map { nodeUid -> ThumbnailBatchResult(setOf(nodeUid), emptyMap()) }
            source.thumbnailResult = ThumbnailBatchResult(setOf("a", "b", "c"), emptyMap())

            sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(
                listOf(setOf("a"), setOf("b", "c")),
                availability.thumbnailsAvailable.map(PublishedThumbnails::timeline),
            )
            assertEquals(0, thumbnails.pendingCount(USER.id))
        }

    @Test
    fun `marks publish again once the interval has passed`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b", "c"))
            source.thumbnailProgress =
                listOf("a", "b", "c").map { nodeUid -> ThumbnailBatchResult(setOf(nodeUid), emptyMap()) }
            source.beforeProgress = { clock.value += ProtonThumbnailWorkPolicy.PROGRESS_PUBLISH_INTERVAL_MILLIS }

            sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(
                listOf(setOf("a"), setOf("b"), setOf("c")),
                availability.thumbnailsAvailable.map(PublishedThumbnails::timeline),
            )
        }

    @Test
    fun `a preview stored in place of a missing thumbnail leaves the preview queue`() =
        runBlocking {
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a"))
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a", "b"))
            source.thumbnailResult = ThumbnailBatchResult(setOf("a"), emptyMap(), previewsStored = setOf("a"))

            sync.downloadNextBatch(USER, allowPreviews = false) {}

            assertEquals(0, thumbnails.pendingCount(USER.id))
            assertEquals(1, previews.pendingCount(USER.id))
            assertEquals(listOf(setOf("a")), availability.previewsAvailable)
        }

    private fun candidates(vararg nodeUids: String) =
        nodeUids.mapIndexed { index, nodeUid -> ProtonThumbnailCandidate(nodeUid, index.toLong()) }

    private class FakeSource : ProtonRenditionSource {
        var thumbnailResult = ThumbnailBatchResult(emptySet(), emptyMap())
        var thumbnailFailure: Throwable? = null
        var thumbnailProgress: List<ThumbnailBatchResult>? = null
        var beforeProgress: () -> Unit = {}
        var previewProgress: List<ThumbnailBatchResult> = emptyList()
        var previewResult = ThumbnailBatchResult(emptySet(), emptyMap())
        var previewCalls = 0
        var storedThumbnails = 0
        var storedPreviews = 0
        val removedThumbnails = mutableListOf<String>()

        override suspend fun downloadThumbnails(
            userId: UserId,
            nodeUids: Collection<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            (thumbnailProgress ?: listOf(thumbnailResult)).forEach { result ->
                beforeProgress()
                onProgress(result)
            }
            thumbnailFailure?.let { throw it }
            return thumbnailResult
        }

        override suspend fun downloadPreviews(
            userId: UserId,
            nodeUids: Collection<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            previewCalls++
            previewProgress.forEach { onProgress(it) }
            return previewResult
        }

        override fun removeThumbnail(
            userId: UserId,
            nodeUid: String,
        ) {
            removedThumbnails += nodeUid
        }

        override fun storedThumbnailCount(userId: UserId): Int = storedThumbnails

        override fun storedPreviewCount(userId: UserId): Int = storedPreviews
    }

    private data class PublishedThumbnails(
        val timeline: Set<String>,
        val albumCovers: Set<String>,
        val albumPhotos: Set<String>,
    )

    private class FakeAvailability : ProtonRenditionAvailability {
        val thumbnailsAvailable = mutableListOf<PublishedThumbnails>()
        val previewsAvailable = mutableListOf<Set<String>>()

        override fun thumbnailsAvailable(
            userId: UserId,
            timelineNodeUids: Set<String>,
            albumCoverNodeUids: Set<String>,
            albumPhotoNodeUids: Set<String>,
        ) {
            thumbnailsAvailable += PublishedThumbnails(timelineNodeUids, albumCoverNodeUids, albumPhotoNodeUids)
        }

        override fun previewsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            previewsAvailable += nodeUids
        }
    }

    private class FakeStore : ProtonThumbnailQueueStore {
        private val queues = mutableMapOf<Pair<String, ProtonQueueName>, List<ProtonThumbnailQueueEntry>>()
        var writeCount = 0

        override fun readQueue(
            userId: String,
            queue: ProtonQueueName,
        ): List<ProtonThumbnailQueueEntry> = queues[userId to queue].orEmpty()

        override fun writeQueue(
            userId: String,
            queue: ProtonQueueName,
            entries: List<ProtonThumbnailQueueEntry>,
        ) {
            writeCount++
            queues[userId to queue] = entries.toList()
        }
    }

    private class FakeClock(
        var value: Long = 1_000L,
    ) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private companion object {
        val USER = UserId("user")
    }
}
