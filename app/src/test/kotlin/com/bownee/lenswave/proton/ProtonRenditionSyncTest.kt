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
    private val sync = ProtonRenditionSync(source, availability, thumbnails, previews)

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

    private fun candidates(vararg nodeUids: String) =
        nodeUids.mapIndexed { index, nodeUid -> ProtonThumbnailCandidate(nodeUid, index.toLong()) }

    private class FakeSource : ProtonRenditionSource {
        var thumbnailResult = ThumbnailBatchResult(emptySet(), emptyMap())
        var thumbnailFailure: Throwable? = null
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
            thumbnailFailure?.let { throw it }
            onProgress(thumbnailResult)
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

        override fun readQueue(
            userId: String,
            queue: ProtonQueueName,
        ): List<ProtonThumbnailQueueEntry> = queues[userId to queue].orEmpty()

        override fun writeQueue(
            userId: String,
            queue: ProtonQueueName,
            entries: List<ProtonThumbnailQueueEntry>,
        ) {
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
