package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class ProtonRenditionSyncTest {
    private val clock = FakeClock()
    private val store = FakeStore()
    private val flushScope = TestScope().backgroundScope
    private val thumbnails = ProtonThumbnailQueue(store, clock, ProtonQueueName.THUMBNAILS, flushScope)
    private val previews = ProtonThumbnailQueue(store, clock, ProtonQueueName.PREVIEWS, flushScope)
    private val source = FakeSource()
    private val availability = FakeAvailability()

    /** Marks are published on the test scheduler, so a [yield] lets the publisher run. */
    private fun test(block: suspend TestScope.(ProtonRenditionSync) -> Unit) =
        runTest {
            block(ProtonRenditionSync(source, availability, thumbnails, previews, clock, backgroundScope))
        }

    @Test
    fun `previews reported only in the final result still leave the queue`() =
        test { sync ->
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a", "b"))
            source.previewProgress = listOf(ThumbnailBatchResult(setOf("a"), emptyMap()))
            source.previewResult = ThumbnailBatchResult(setOf("a", "b"), emptyMap())

            val step = sync.downloadNextBatch(USER, allowPreviews = true) {}
            sync.finishPublishing(USER)

            assertEquals(ProtonThumbnailQueueStep.Processed, step)
            assertEquals(0, previews.pendingCount(USER.id))
            assertEquals(setOf("a", "b"), availability.previewsAvailable.flatten().toSet())
        }

    @Test
    fun `a preview failure reported in progress and in the result is one backoff step`() =
        test { sync ->
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a", "b"))
            val failure = mapOf("b" to ThumbnailFailureKind.OTHER)
            source.previewProgress = listOf(ThumbnailBatchResult(setOf("a"), failure))
            source.previewResult = ThumbnailBatchResult(setOf("a"), failure)

            sync.downloadNextBatch(USER, allowPreviews = true) {}
            previews.flush(USER.id)

            val entry = store.readQueue(USER.id, ProtonQueueName.PREVIEWS).single()
            assertEquals("b", entry.nodeUid)
            assertEquals(1, entry.retryCount)
        }

    @Test
    fun `a preview Proton does not have leaves the queue for good`() =
        test { sync ->
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("gone", "later"))
            source.previewResult =
                ThumbnailBatchResult(
                    emptySet(),
                    mapOf("gone" to ThumbnailFailureKind.NOT_FOUND, "later" to ThumbnailFailureKind.OTHER),
                )

            sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(1, previews.pendingCount(USER.id))
            clock.value += 30_000L
            assertEquals(listOf("later"), previews.claimReady(USER.id, limit = 2).map { it.nodeUid })
        }

    @Test
    fun `a processed batch leaves the write to the debounce`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailResult = ThumbnailBatchResult(setOf("a", "b"), emptyMap())
            val writesBefore = store.writeCount

            sync.downloadNextBatch(USER, allowPreviews = true) {}

            assertEquals(writesBefore, store.writeCount)
            assertEquals(0, thumbnails.pendingCount(USER.id))
        }

    @Test
    fun `every Nth batch and the idle step force a write`() =
        test { sync ->
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
        test { sync ->
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
        test { sync ->
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
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("x"))
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.ALBUM_COVERS, candidates("x", "cover"))
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("p"))
            source.thumbnailResult = ThumbnailBatchResult(setOf("x", "cover", "orphan"), emptyMap())

            sync.downloadNextBatch(USER, allowPreviews = true) {}
            sync.finishPublishing(USER)

            assertEquals(0, thumbnails.pendingCount(USER.id))
            assertEquals(1, previews.pendingCount(USER.id))
            assertEquals(listOf("orphan"), source.removedThumbnails)
            val published = availability.thumbnailsAvailable.single()
            assertEquals(setOf("x"), published.timeline)
            assertEquals(setOf("x", "cover"), published.albumCovers)
            assertTrue(published.albumPhotos.isEmpty())
        }

    @Test
    fun `a thumbnail reported twice in one batch is kept`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailProgress =
                listOf(
                    ThumbnailBatchResult(setOf("a"), emptyMap()),
                    ThumbnailBatchResult(setOf("a", "b"), emptyMap()),
                )

            sync.downloadNextBatch(USER, allowPreviews = true) {}
            sync.finishPublishing(USER)

            assertTrue(
                "a settled thumbnail must not be deleted: ${source.removedThumbnails}",
                source.removedThumbnails.isEmpty(),
            )
            assertEquals(0, thumbnails.pendingCount(USER.id))
            assertEquals(
                setOf("a", "b"),
                availability.thumbnailsAvailable.flatMap(PublishedThumbnails::timeline).toSet(),
            )
        }

    @Test
    fun `previews wait for the charger without being claimed`() =
        test { sync ->
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
        test { sync ->
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
    fun `a connection lost under the batch costs no node a backoff step`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailFailure = UnknownHostException("api.proton.me")

            val step = sync.downloadNextBatch(USER, allowPreviews = true) {}
            thumbnails.flush(USER.id)

            assertEquals(ProtonThumbnailQueueStep.Processed, step)
            assertEquals(2, thumbnails.pendingCount(USER.id))
            assertTrue(store.readQueue(USER.id, ProtonQueueName.THUMBNAILS).all { entry -> entry.retryCount == 0 })
            assertTrue(thumbnails.claimReady(USER.id, limit = 2).isEmpty())
            clock.value += ProtonThumbnailQueue.NETWORK_RETRY_MILLIS
            assertEquals(2, thumbnails.claimReady(USER.id, limit = 2).size)
        }

    @Test
    fun `progress counts stored and pending renditions of both queues`() =
        test { sync ->
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
    fun `thumbnail marks are coalesced within the publish interval and flushed when the run idles`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b", "c"))
            source.thumbnailProgress =
                listOf("a", "b", "c").map { nodeUid -> ThumbnailBatchResult(setOf(nodeUid), emptyMap()) }
            source.thumbnailResult = ThumbnailBatchResult(setOf("a", "b", "c"), emptyMap())
            source.beforeProgress = { yield() }

            sync.downloadNextBatch(USER, allowPreviews = true) {}
            assertEquals(
                ProtonThumbnailQueueStep.Idle(hasPending = false),
                sync.downloadNextBatch(USER, allowPreviews = true) {},
            )

            assertEquals(
                listOf(setOf("a"), setOf("b", "c")),
                availability.thumbnailsAvailable.map(PublishedThumbnails::timeline),
            )
            assertEquals(0, thumbnails.pendingCount(USER.id))
        }

    @Test
    fun `the publish interval spans batches instead of starting over with each`() =
        test { sync ->
            source.beforeProgress = { yield() }

            suspend fun queued(vararg nodeUids: String) =
                nodeUids.forEach { nodeUid ->
                    thumbnails.retryNow(
                        USER.id,
                        ProtonThumbnailCandidate(nodeUid, 1L),
                        setOf(ProtonSyncKeys.QueueSource.TIMELINE),
                    )
                }

            queued("c")
            source.thumbnailResult = ThumbnailBatchResult(setOf("c"), emptyMap())
            assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = true) {})
            queued("a", "b")
            source.thumbnailResult = ThumbnailBatchResult(setOf("a", "b"), emptyMap())
            assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = true) {})
            assertEquals(
                ProtonThumbnailQueueStep.Idle(hasPending = false),
                sync.downloadNextBatch(USER, allowPreviews = true) {},
            )

            // The first batch's mark went out at once; the second batch's waited for the
            // interval that started then, rather than going out at once again.
            assertEquals(
                listOf(setOf("c"), setOf("a", "b")),
                availability.thumbnailsAvailable.map(PublishedThumbnails::timeline),
            )
        }

    @Test
    fun `marks publish again once the interval has passed`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b", "c"))
            source.thumbnailProgress =
                listOf("a", "b", "c").map { nodeUid -> ThumbnailBatchResult(setOf(nodeUid), emptyMap()) }
            source.beforeProgress = {
                clock.value += ProtonThumbnailWorkPolicy.PROGRESS_PUBLISH_INTERVAL_MILLIS
                yield()
            }

            sync.downloadNextBatch(USER, allowPreviews = true) {}
            sync.finishPublishing(USER)

            assertEquals(
                listOf(setOf("a"), setOf("b"), setOf("c")),
                availability.thumbnailsAvailable.map(PublishedThumbnails::timeline),
            )
        }

    @Test
    fun `marks are published off the download coroutine and finished when the run ends`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailProgress =
                listOf("a", "b").map { nodeUid -> ThumbnailBatchResult(setOf(nodeUid), emptyMap()) }
            val publishedDuringProgress = mutableListOf<Int>()

            sync.downloadNextBatch(USER, allowPreviews = true) {
                publishedDuringProgress += availability.thumbnailsAvailable.size
            }
            sync.finishPublishing(USER)

            // The progress callback returned before any listing was touched: the publication
            // ran on the sync's own coroutine, and was complete when the run was finished.
            assertEquals(listOf(0, 0), publishedDuringProgress)
            assertEquals(listOf(setOf("a", "b")), availability.thumbnailsAvailable.map(PublishedThumbnails::timeline))
        }

    @Test
    fun `a stopped batch still publishes what it stored`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            source.thumbnailProgress = listOf(ThumbnailBatchResult(setOf("a"), emptyMap()))
            source.thumbnailFailure = kotlinx.coroutines.CancellationException("stopped")

            runCatching { sync.downloadNextBatch(USER, allowPreviews = true) {} }

            assertEquals(listOf(setOf("a")), availability.thumbnailsAvailable.map(PublishedThumbnails::timeline))
        }

    @Test
    fun `thumbnails deferred to a preview-fetching run are parked, not claimed again and again`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            // Proton has no thumbnail for either and previews are not allowed: the downloader
            // reports both as deferred, in the result only.
            source.thumbnailProgress = emptyList()
            source.thumbnailResult = ThumbnailBatchResult(emptySet(), emptyMap(), deferredNodeUids = setOf("a", "b"))

            assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = false) {})
            val idle = sync.downloadNextBatch(USER, allowPreviews = false) {}

            // The run ends with a charging follow-up instead of claiming, enumerating and
            // releasing the same two nodes every second until its deadline.
            assertEquals(ProtonThumbnailQueueStep.Idle(hasPending = false, previewsDeferred = true), idle)
            assertEquals(1, source.thumbnailCalls)
            assertEquals(2, thumbnails.pendingCount(USER.id))
            thumbnails.flush(USER.id)
            assertTrue(store.readQueue(USER.id, ProtonQueueName.THUMBNAILS).all { entry -> entry.retryCount == 0 })

            // A run that may fetch previews serves them at once.
            source.thumbnailResult = ThumbnailBatchResult(setOf("a", "b"), emptyMap(), previewsStored = setOf("a", "b"))
            assertEquals(ProtonThumbnailQueueStep.Processed, sync.downloadNextBatch(USER, allowPreviews = true) {})
            assertEquals(listOf(listOf("a", "b")), source.thumbnailRequests.drop(1).map { it.sorted() })
            assertEquals(0, thumbnails.pendingCount(USER.id))
        }

    @Test
    fun `a thumbnail failure reported in progress and in the result is one backoff step`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a", "b"))
            val failure = mapOf("b" to ThumbnailFailureKind.OTHER)
            source.thumbnailProgress = listOf(ThumbnailBatchResult(setOf("a"), failure))
            source.thumbnailResult = ThumbnailBatchResult(setOf("a"), failure)

            sync.downloadNextBatch(USER, allowPreviews = true) {}
            thumbnails.flush(USER.id)

            val entry = store.readQueue(USER.id, ProtonQueueName.THUMBNAILS).single()
            assertEquals("b", entry.nodeUid)
            assertEquals(1, entry.retryCount)
        }

    @Test
    fun `a preview stored in place of a missing thumbnail leaves the preview queue`() =
        test { sync ->
            thumbnails.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE, candidates("a"))
            previews.replaceSource(USER.id, ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS, candidates("a", "b"))
            source.thumbnailResult = ThumbnailBatchResult(setOf("a"), emptyMap(), previewsStored = setOf("a"))

            sync.downloadNextBatch(USER, allowPreviews = false) {}
            sync.finishPublishing(USER)

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
        var beforeProgress: suspend () -> Unit = {}
        var previewProgress: List<ThumbnailBatchResult> = emptyList()
        var previewResult = ThumbnailBatchResult(emptySet(), emptyMap())
        var previewCalls = 0
        var thumbnailCalls = 0
        val thumbnailRequests = mutableListOf<List<String>>()
        var storedThumbnails = 0
        var storedPreviews = 0
        val removedThumbnails = mutableListOf<String>()

        override suspend fun downloadThumbnails(
            userId: UserId,
            nodeUids: Collection<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            thumbnailCalls++
            thumbnailRequests += nodeUids.toList()
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
