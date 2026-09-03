package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailWorkPolicyTest {
    @Test
    fun `foreground run is long lived but below the Android data sync limit`() {
        assertTrue(ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS > 10L * 60L * 1_000L)
        assertTrue(ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS < 6L * 60L * 60L * 1_000L)
    }

    @Test
    fun `complete download succeeds immediately`() {
        val resolution = ProtonThumbnailWorkPolicy.resolve(runAttemptCount = 0, issue = null)

        assertEquals(ProtonThumbnailWorkDecision.SUCCESS, resolution.decision)
        assertEquals(null, resolution.status)
        assertEquals("complete", resolution.diagnosticState)
    }

    @Test
    fun `incomplete download retries before the attempt limit`() {
        val resolution = ProtonThumbnailWorkPolicy.resolve(
            runAttemptCount = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS - 2,
            issue = ProtonThumbnailWorkIssue.INCOMPLETE,
        )

        assertEquals(ProtonThumbnailWorkDecision.RETRY, resolution.decision)
        assertEquals(
            ProtonThumbnailWorkStatus.RetryScheduled(
                ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
                ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
                ProtonThumbnailWorkIssue.INCOMPLETE,
            ),
            resolution.status,
        )
        assertEquals("retry-incomplete", resolution.diagnosticState)
    }

    @Test
    fun `incomplete download fails at the attempt limit`() {
        val resolution = ProtonThumbnailWorkPolicy.resolve(
            runAttemptCount = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS - 1,
            issue = ProtonThumbnailWorkIssue.TIMEOUT,
        )

        assertEquals(ProtonThumbnailWorkDecision.FAILURE, resolution.decision)
        assertEquals(
            ProtonThumbnailWorkStatus.Stopped(
                ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
                ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
                ProtonThumbnailWorkIssue.TIMEOUT,
            ),
            resolution.status,
        )
        assertEquals("stopped-timeout", resolution.diagnosticState)
    }
}
