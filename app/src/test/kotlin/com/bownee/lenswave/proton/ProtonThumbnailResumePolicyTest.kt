package com.bownee.lenswave.proton

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailResumePolicyTest {
    @Test
    fun delayedAndRetriedWorkIsReplacedWhenTheAppReturnsToForeground() {
        assertTrue(ProtonThumbnailResumePolicy.shouldReplace(listOf(WorkInfo.State.ENQUEUED)))
    }

    @Test
    fun anActiveDownloadIsKeptWhenTheAppReturnsToForeground() {
        assertFalse(ProtonThumbnailResumePolicy.shouldReplace(listOf(WorkInfo.State.RUNNING)))
    }

    @Test
    fun blockedWorkIsReplaced() {
        assertTrue(ProtonThumbnailResumePolicy.shouldReplace(listOf(WorkInfo.State.BLOCKED)))
    }

    @Test
    fun finishedWorkIsReplacedSoNewQueueEntriesAreDownloaded() {
        assertTrue(ProtonThumbnailResumePolicy.shouldReplace(listOf(WorkInfo.State.SUCCEEDED)))
        assertTrue(ProtonThumbnailResumePolicy.shouldReplace(listOf(WorkInfo.State.FAILED)))
        assertTrue(ProtonThumbnailResumePolicy.shouldReplace(listOf(WorkInfo.State.CANCELLED)))
    }

    @Test
    fun missingWorkIsReplaced() {
        assertTrue(ProtonThumbnailResumePolicy.shouldReplace(emptyList()))
    }

    @Test
    fun anyRunningWorkerKeepsTheWholeChain() {
        assertFalse(
            ProtonThumbnailResumePolicy.shouldReplace(
                listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED),
            ),
        )
    }
}
