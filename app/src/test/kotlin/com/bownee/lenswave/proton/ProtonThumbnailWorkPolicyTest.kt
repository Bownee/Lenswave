package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val resolution =
            ProtonThumbnailWorkPolicy.resolve(
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
        val resolution =
            ProtonThumbnailWorkPolicy.resolve(
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

    @Test
    fun `attempt count remains valid when restored work exceeds the limit`() {
        val resolution =
            ProtonThumbnailWorkPolicy.resolve(
                runAttemptCount = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS + 3,
                issue = ProtonThumbnailWorkIssue.ERROR,
            )

        assertEquals(ProtonThumbnailWorkDecision.FAILURE, resolution.decision)
        assertEquals(
            ProtonThumbnailWorkStatus.Stopped(
                ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
                ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
                ProtonThumbnailWorkIssue.ERROR,
            ),
            resolution.status,
        )
        assertEquals("stopped-error", resolution.diagnosticState)
    }

    @Test
    fun `previews wait for the charger unless the app is on screen`() {
        assertTrue(ProtonThumbnailWorkPolicy.previewsAllowed(charging = true, appVisible = false))
        assertTrue(ProtonThumbnailWorkPolicy.previewsAllowed(charging = false, appVisible = true))
        assertFalse(ProtonThumbnailWorkPolicy.previewsAllowed(charging = false, appVisible = false))
    }

    @Test
    fun `deferred previews are not pending work for the run`() {
        val previewsOnly = ProtonThumbnailWorkProgress(stored = 5, pending = 0, previewsPending = 3)

        assertTrue(ProtonThumbnailWorkPolicy.hasPendingWork(previewsOnly, allowPreviews = true))
        assertFalse(ProtonThumbnailWorkPolicy.hasPendingWork(previewsOnly, allowPreviews = false))
        assertTrue(
            ProtonThumbnailWorkPolicy.hasPendingWork(
                ProtonThumbnailWorkProgress(stored = 5, pending = 1),
                allowPreviews = false,
            ),
        )
    }

    @Test
    fun `progress notifications are rate limited unless forced`() {
        val interval = ProtonThumbnailWorkPolicy.PROGRESS_PUBLISH_INTERVAL_MILLIS

        assertTrue(ProtonThumbnailWorkPolicy.shouldPublishProgress(null, 10L, force = false))
        assertFalse(ProtonThumbnailWorkPolicy.shouldPublishProgress(10L, 10L + interval - 1, force = false))
        assertTrue(ProtonThumbnailWorkPolicy.shouldPublishProgress(10L, 10L + interval, force = false))
        assertTrue(ProtonThumbnailWorkPolicy.shouldPublishProgress(10L, 11L, force = true))
    }
}
