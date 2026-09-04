package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonOriginalProgressPolicyTest {
    @Test
    fun `a write that does not move the percentage is not published`() {
        val previous = ProtonOriginalDownloadProgress(downloadedBytes = 1_000L, totalBytes = 100_000L)

        assertFalse(ProtonOriginalProgressPolicy.shouldPublish(previous, previous.copy(downloadedBytes = 1_500L)))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, previous.copy(downloadedBytes = 2_000L)))
    }

    @Test
    fun `without a total, publication waits for a meaningful byte delta`() {
        val previous = ProtonOriginalDownloadProgress(downloadedBytes = 0L, totalBytes = null)
        val small = previous.copy(downloadedBytes = ProtonOriginalProgressPolicy.MIN_BYTE_DELTA - 1L)
        val enough = previous.copy(downloadedBytes = ProtonOriginalProgressPolicy.MIN_BYTE_DELTA)

        assertFalse(ProtonOriginalProgressPolicy.shouldPublish(previous, small))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, enough))
    }

    @Test
    fun `a new total, completion or a rewind always publish`() {
        val previous = ProtonOriginalDownloadProgress(downloadedBytes = 10L, totalBytes = null)

        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, previous.copy(totalBytes = 1_000L)))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, previous.copy(complete = true)))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, previous.copy(downloadedBytes = 5L)))
        assertFalse(ProtonOriginalProgressPolicy.shouldPublish(previous, previous))
    }
}
