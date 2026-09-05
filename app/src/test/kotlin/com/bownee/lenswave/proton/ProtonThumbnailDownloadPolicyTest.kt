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
    fun thePassDeadlineFollowsTheRenditionNotTheQueue() {
        assertEquals(
            ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS,
            ProtonThumbnailDownloadPolicy.passTimeoutMillis(ThumbnailType.PREVIEW),
        )
        assertEquals(
            ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS,
            ProtonThumbnailDownloadPolicy.passTimeoutMillis(ThumbnailType.THUMBNAIL),
        )
        assertTrue(
            ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS >
                ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS,
        )
    }

    @Test
    fun bothRenditionsWaitTheSameForTheFirstAnswer() {
        val firstAnswer = ProtonThumbnailDownloadPolicy.FIRST_ANSWER_TIMEOUT_MILLIS

        // The batch setup the allowance covers is the same whichever rendition follows it.
        assertEquals(
            firstAnswer,
            ProtonThumbnailDownloadPolicy.answerWaitMillis(
                ThumbnailType.THUMBNAIL,
                answered = false,
                ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS,
            ),
        )
        assertEquals(
            firstAnswer,
            ProtonThumbnailDownloadPolicy.answerWaitMillis(
                ThumbnailType.PREVIEW,
                answered = false,
                ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS,
            ),
        )
    }

    @Test
    fun firstAnswerAllowanceFitsUnderEachDeadlineAndOverTheIdleWindow() {
        val firstAnswer = ProtonThumbnailDownloadPolicy.FIRST_ANSWER_TIMEOUT_MILLIS

        assertTrue(firstAnswer <= ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS)
        assertTrue(firstAnswer < ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS)
        assertTrue(firstAnswer > ProtonThumbnailDownloadPolicy.idleTimeoutMillis(ThumbnailType.PREVIEW))
    }

    @Test
    fun aDeadlineShorterThanTheAllowanceCapsTheFirstAnswerWait() {
        val shortDeadline = ProtonThumbnailDownloadPolicy.FIRST_ANSWER_TIMEOUT_MILLIS / 4

        assertEquals(
            shortDeadline,
            ProtonThumbnailDownloadPolicy.answerWaitMillis(ThumbnailType.THUMBNAIL, answered = false, shortDeadline),
        )
    }

    @Test
    fun aNodeReAskedOnItsOwnWaitsLessForItsFirstAnswer() {
        val singleNode = ProtonThumbnailDownloadPolicy.SINGLE_NODE_FIRST_ANSWER_TIMEOUT_MILLIS

        assertTrue(singleNode < ProtonThumbnailDownloadPolicy.FIRST_ANSWER_TIMEOUT_MILLIS)
        assertTrue(singleNode > ProtonThumbnailDownloadPolicy.idleTimeoutMillis(ThumbnailType.PREVIEW))
        assertEquals(
            singleNode,
            ProtonThumbnailDownloadPolicy.answerWaitMillis(
                ThumbnailType.THUMBNAIL,
                answered = false,
                ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS,
                firstAnswerTimeoutMillis = singleNode,
            ),
        )
        assertEquals(
            singleNode,
            ProtonThumbnailDownloadPolicy.answerWaitMillis(
                ThumbnailType.PREVIEW,
                answered = false,
                ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS,
                firstAnswerTimeoutMillis = singleNode,
            ),
        )
    }

    @Test
    fun idleWindowAppliesOnceTheSdkHasAnswered() {
        val deadline = ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS

        assertEquals(
            ProtonThumbnailDownloadPolicy.idleTimeoutMillis(ThumbnailType.THUMBNAIL),
            ProtonThumbnailDownloadPolicy.answerWaitMillis(ThumbnailType.THUMBNAIL, answered = true, deadline),
        )
        assertEquals(
            ProtonThumbnailDownloadPolicy.idleTimeoutMillis(ThumbnailType.PREVIEW),
            ProtonThumbnailDownloadPolicy.answerWaitMillis(ThumbnailType.PREVIEW, answered = true, deadline),
        )
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
