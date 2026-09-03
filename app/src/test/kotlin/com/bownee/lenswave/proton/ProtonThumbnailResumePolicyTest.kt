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
}
