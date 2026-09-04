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
    fun `a crashed run is retried a few times and then left alone`() {
        assertTrue(ProtonThumbnailWorkPolicy.shouldRetryAfterError(runAttemptCount = 0))
        assertTrue(ProtonThumbnailWorkPolicy.shouldRetryAfterError(runAttemptCount = 1))
        assertFalse(ProtonThumbnailWorkPolicy.shouldRetryAfterError(runAttemptCount = 2))
        assertFalse(ProtonThumbnailWorkPolicy.shouldRetryAfterError(runAttemptCount = 40))
    }

    @Test
    fun `every outcome has a log-safe diagnostic state`() {
        val safe = Regex("[a-z-]{1,64}")
        ProtonThumbnailWorkOutcome.entries.forEach { outcome ->
            assertTrue(outcome.name, safe.matches(outcome.diagnosticState))
        }
        assertEquals("complete", ProtonThumbnailWorkOutcome.COMPLETE.diagnosticState)
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
