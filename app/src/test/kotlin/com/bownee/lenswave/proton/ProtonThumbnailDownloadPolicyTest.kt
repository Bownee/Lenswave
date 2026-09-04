package com.bownee.lenswave.proton

import me.proton.drive.sdk.entity.ThumbnailType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailDownloadPolicyTest {
    @Test
    fun sdkBatchesStayBoundedAndKeepTheirOrder() {
        val nodeUids = (1..65).map { value -> value.toString() }

        val batches = ProtonThumbnailDownloadPolicy.batches(nodeUids)

        assertEquals(nodeUids, batches.flatten())
        assertTrue(batches.all { batch -> batch.size <= ProtonThumbnailDownloadPolicy.SDK_BATCH_SIZE })
        assertEquals(9, batches.size)
    }

    @Test
    fun singleNodePassesRunMoreConcurrentlyThanFullBatches() {
        assertTrue(
            ProtonThumbnailDownloadPolicy.MAX_CONCURRENT_SINGLE_NODE_PASSES >
                ProtonThumbnailDownloadPolicy.MAX_CONCURRENT_BATCHES,
        )
    }

    @Test
    fun progressIsPublishedBeforeAnSdkBatchCanComplete() {
        assertTrue(
            ProtonThumbnailDownloadPolicy.PROGRESS_BATCH_SIZE <
                ProtonThumbnailDownloadPolicy.SDK_BATCH_SIZE,
        )
        assertTrue(ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS > 0L)
    }

    @Test
    fun sdkPassCompletesAsSoonAsEveryRequestedNodeHasResponded() {
        val requested = setOf("success", "failure")

        assertTrue(
            ThumbnailPassCompletionPolicy.hasResponseForEveryNode(
                requested,
                successfulNodeUids = setOf("success"),
                failedNodeUids = setOf("failure"),
            ),
        )
        assertFalse(
            ThumbnailPassCompletionPolicy.hasResponseForEveryNode(
                requested,
                successfulNodeUids = setOf("success"),
                failedNodeUids = emptySet(),
            ),
        )
    }

    @Test
    fun singleNodeBatchesRetryUnansweredNodesOneAtATime() {
        val batches = ProtonThumbnailDownloadPolicy.batches(listOf("a", "b", "c"), batchSize = 1)

        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), batches)
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchSizeMustBePositive() {
        ProtonThumbnailDownloadPolicy.batches(listOf("a"), batchSize = 0)
    }

    @Test
    fun idleTimeoutIsLongerForPreviewsButBelowEachDeadline() {
        val previewIdle = ProtonThumbnailDownloadPolicy.idleTimeoutMillis(ThumbnailType.PREVIEW)
        val thumbnailIdle = ProtonThumbnailDownloadPolicy.idleTimeoutMillis(ThumbnailType.THUMBNAIL)

        assertTrue(previewIdle > thumbnailIdle)
        assertTrue(previewIdle < ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS)
        assertTrue(thumbnailIdle < ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS)
    }
}
