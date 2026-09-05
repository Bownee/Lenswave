package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonOriginalProgressPolicyTest {
    @Test
    fun `a write that does not move the percentage is not published`() {
        val previous = ProtonOriginalDownloadProgress(downloadedBytes = 1_000L, totalBytes = 100_000L)

        assertFalse(ProtonOriginalProgressPolicy.shouldPublish(previous, 1_500L, 100_000L, complete = false))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, 2_000L, 100_000L, complete = false))
    }

    @Test
    fun `without a total, publication waits for a meaningful byte delta`() {
        val previous = ProtonOriginalDownloadProgress(downloadedBytes = 0L, totalBytes = null)
        val small = ProtonOriginalProgressPolicy.MIN_BYTE_DELTA - 1L
        val enough = ProtonOriginalProgressPolicy.MIN_BYTE_DELTA

        assertFalse(ProtonOriginalProgressPolicy.shouldPublish(previous, small, null, complete = false))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, enough, null, complete = false))
    }

    @Test
    fun `a new total, completion or a rewind always publish`() {
        val previous = ProtonOriginalDownloadProgress(downloadedBytes = 10L, totalBytes = null)

        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, 10L, 1_000L, complete = false))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, 10L, null, complete = true))
        assertTrue(ProtonOriginalProgressPolicy.shouldPublish(previous, 5L, null, complete = false))
        assertFalse(ProtonOriginalProgressPolicy.shouldPublish(previous, 10L, null, complete = false))
    }

    @Test
    fun `the primitive rule matches the value it would build`() {
        assertTrue(
            ProtonOriginalProgressPolicy.shouldPublish(
                previousDownloadedBytes = 1_000L,
                previousTotalBytes = 100_000L,
                previousComplete = false,
                downloadedBytes = 2_000L,
                totalBytes = 100_000L,
                complete = false,
            ),
        )
        assertFalse(
            ProtonOriginalProgressPolicy.shouldPublish(
                previousDownloadedBytes = 1_000L,
                previousTotalBytes = 100_000L,
                previousComplete = false,
                downloadedBytes = 1_999L,
                totalBytes = 100_000L,
                complete = false,
            ),
        )
    }

    @Test
    fun `percent needs a positive total and clamps the downloaded bytes`() {
        assertNull(ProtonOriginalProgressPolicy.percent(10L, null))
        assertNull(ProtonOriginalProgressPolicy.percent(10L, 0L))
        assertEquals(25, ProtonOriginalProgressPolicy.percent(250L, 1_000L))
        assertEquals(100, ProtonOriginalProgressPolicy.percent(5_000L, 1_000L))
        assertEquals(0, ProtonOriginalProgressPolicy.percent(-5L, 1_000L))
        assertEquals(25, ProtonOriginalDownloadProgress(250L, 1_000L).percent)
    }
}
