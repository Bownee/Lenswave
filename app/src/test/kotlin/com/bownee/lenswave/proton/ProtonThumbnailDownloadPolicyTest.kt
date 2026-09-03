package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailDownloadPolicyTest {
    @Test
    fun sdkBatchesAndConcurrentWindowsStayBounded() {
        val nodeUids = (1..65).map { value -> value.toString() }

        val windows = ProtonThumbnailDownloadPolicy.concurrentWindows(nodeUids)

        assertEquals(nodeUids, windows.flatten().flatten())
        assertTrue(
            windows.all { window ->
                window.size <= ProtonThumbnailDownloadPolicy.MAX_CONCURRENT_BATCHES
            },
        )
        assertTrue(
            windows.flatten().all { batch ->
                batch.size <= ProtonThumbnailDownloadPolicy.SDK_BATCH_SIZE
            },
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
}
