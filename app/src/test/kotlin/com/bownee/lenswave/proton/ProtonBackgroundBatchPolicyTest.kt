package com.bownee.lenswave.proton

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonBackgroundBatchPolicyTest {
    @Test
    fun thumbnailsAreServedWithoutTouchingThePreviewQueue() =
        runBlocking {
            var previewClaims = 0
            val batch =
                ProtonBackgroundBatchPolicy.choose(thumbnailBatch = entries("a", "b")) {
                    previewClaims++
                    entries("preview")
                }

            assertEquals(ProtonQueueName.THUMBNAILS, batch?.queue)
            assertEquals(listOf("a", "b"), batch?.entries?.map(ProtonThumbnailQueueEntry::nodeUid))
            assertEquals(0, previewClaims)
        }

    @Test
    fun previewsAreClaimedOnlyWhenNoThumbnailIsReady() =
        runBlocking {
            val batch = ProtonBackgroundBatchPolicy.choose(thumbnailBatch = emptyList()) { entries("preview") }

            assertEquals(ProtonQueueName.PREVIEWS, batch?.queue)
            assertEquals(listOf("preview"), batch?.entries?.map(ProtonThumbnailQueueEntry::nodeUid))
        }

    @Test
    fun nothingReadyYieldsNoBatch() =
        runBlocking {
            assertNull(ProtonBackgroundBatchPolicy.choose(thumbnailBatch = emptyList()) { emptyList() })
        }

    @Test
    fun idleReportsPendingWorkFromEitherQueue() {
        assertFalse(ProtonBackgroundBatchPolicy.idle(thumbnailsPending = false, previewsPending = false).hasPending)
        assertTrue(ProtonBackgroundBatchPolicy.idle(thumbnailsPending = true, previewsPending = false).hasPending)
        assertTrue(ProtonBackgroundBatchPolicy.idle(thumbnailsPending = false, previewsPending = true).hasPending)
    }

    @Test
    fun onlyMissingPreviewsArePermanentFailures() {
        assertTrue(ProtonBackgroundBatchPolicy.isPermanent(ThumbnailFailureKind.NOT_FOUND))
        ThumbnailFailureKind.entries
            .filterNot { kind -> kind == ThumbnailFailureKind.NOT_FOUND }
            .forEach { kind -> assertFalse(kind.name, ProtonBackgroundBatchPolicy.isPermanent(kind)) }
    }

    private fun entries(vararg nodeUids: String) =
        nodeUids.map { nodeUid -> ProtonThumbnailQueueEntry(nodeUid, mapOf("timeline" to 1L)) }
}
