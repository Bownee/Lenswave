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
    fun previewsAreNotClaimedWhileTheyWaitForTheCharger() =
        runBlocking {
            var previewClaims = 0
            val batch =
                ProtonBackgroundBatchPolicy.choose(thumbnailBatch = emptyList(), allowPreviews = false) {
                    previewClaims++
                    entries("preview")
                }

            assertNull(batch)
            assertEquals(0, previewClaims)
        }

    @Test
    fun deferredPreviewsAreFlaggedButNotPending() {
        val idle =
            ProtonBackgroundBatchPolicy.idle(
                thumbnailsPending = false,
                previewsPending = true,
                previewRetryDelayMillis = 5_000L,
                allowPreviews = false,
            )

        assertFalse(idle.hasPending)
        assertTrue(idle.previewsDeferred)
        assertNull(idle.retryAfterMillis)
        assertFalse(
            ProtonBackgroundBatchPolicy.idle(thumbnailsPending = true, previewsPending = false).previewsDeferred,
        )
    }

    @Test
    fun idleReportsPendingWorkFromEitherQueue() {
        assertFalse(ProtonBackgroundBatchPolicy.idle(thumbnailsPending = false, previewsPending = false).hasPending)
        assertTrue(ProtonBackgroundBatchPolicy.idle(thumbnailsPending = true, previewsPending = false).hasPending)
        assertTrue(ProtonBackgroundBatchPolicy.idle(thumbnailsPending = false, previewsPending = true).hasPending)
    }

    private fun entries(vararg nodeUids: String) =
        nodeUids.map { nodeUid -> ProtonThumbnailQueueEntry(nodeUid, mapOf("timeline" to 1L)) }

    @Test
    fun `idle runs wait for soon-due retries and end otherwise`() {
        val idle =
            ProtonBackgroundBatchPolicy.idle(
                thumbnailsPending = false,
                previewsPending = true,
                thumbnailRetryDelayMillis = null,
                previewRetryDelayMillis = 90_000L,
            )
        assertEquals(90_000L, idle.retryAfterMillis)
        assertEquals(90_000L, ProtonBackgroundBatchPolicy.idleWaitMillis(idle, maxWaitMillis = 120_000L))
        assertNull(ProtonBackgroundBatchPolicy.idleWaitMillis(idle, maxWaitMillis = 60_000L))
        assertNull(
            ProtonBackgroundBatchPolicy.idleWaitMillis(ProtonThumbnailQueueStep.Idle(hasPending = false), 120_000L),
        )
        assertEquals(
            5L,
            ProtonBackgroundBatchPolicy
                .idle(
                    true,
                    true,
                    thumbnailRetryDelayMillis = 5L,
                    previewRetryDelayMillis = 9L,
                ).retryAfterMillis,
        )
    }
}
