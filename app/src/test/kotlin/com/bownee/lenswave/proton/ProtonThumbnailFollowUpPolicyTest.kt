package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtonThumbnailFollowUpPolicyTest {
    @Test
    fun `a lost network is followed up as soon as an unmetered one is back`() {
        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = 0L),
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, workRemaining = true),
        )
    }

    @Test
    fun `a run that hit its limit is followed up at once while work remains`() {
        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = 0L),
            followUp(ProtonThumbnailWorkOutcome.TIMED_OUT, workRemaining = true, retryAfterMillis = 60_000L),
        )
    }

    @Test
    fun `a backoff becomes the follow-up's initial delay within bounds`() {
        val min = ProtonThumbnailFollowUpPolicy.MIN_RETRY_DELAY_MILLIS
        val max = ProtonThumbnailFollowUpPolicy.MAX_RETRY_DELAY_MILLIS

        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = 90_000L),
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, workRemaining = true, retryAfterMillis = 90_000L),
        )
        assertEquals(
            min,
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, workRemaining = true, retryAfterMillis = 1L)
                ?.initialDelayMillis,
        )
        assertEquals(
            min,
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, workRemaining = true, retryAfterMillis = null)
                ?.initialDelayMillis,
        )
        assertEquals(
            max,
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, workRemaining = true, retryAfterMillis = max * 4)
                ?.initialDelayMillis,
        )
    }

    @Test
    fun `deferred previews get a charging run when nothing else remains`() {
        val charging = ProtonThumbnailFollowUp(requiresCharging = true, initialDelayMillis = 0L)

        assertEquals(charging, followUp(ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED, previewsDeferred = true))
        assertEquals(charging, followUp(ProtonThumbnailWorkOutcome.COMPLETE, previewsDeferred = true))
        assertEquals(charging, followUp(ProtonThumbnailWorkOutcome.TIMED_OUT, previewsDeferred = true))
        assertEquals(charging, followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, previewsDeferred = true))
        assertEquals(charging, followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, previewsDeferred = true))
    }

    @Test
    fun `thumbnails still pending come before the charging run`() {
        assertEquals(
            false,
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, workRemaining = true, previewsDeferred = true)
                ?.requiresCharging,
        )
    }

    @Test
    fun `a finished run and a run that was not the active one need no follow-up`() {
        assertNull(followUp(ProtonThumbnailWorkOutcome.COMPLETE))
        assertNull(
            followUp(ProtonThumbnailWorkOutcome.SESSION_UNAVAILABLE, workRemaining = true, previewsDeferred = true),
        )
        assertNull(followUp(ProtonThumbnailWorkOutcome.ALREADY_RUNNING, workRemaining = true, previewsDeferred = true))
        assertNull(followUp(ProtonThumbnailWorkOutcome.PAUSED, workRemaining = true, previewsDeferred = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a follow-up cannot be due in the past`() {
        ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = -1L)
    }

    private fun followUp(
        outcome: ProtonThumbnailWorkOutcome,
        workRemaining: Boolean = false,
        previewsDeferred: Boolean = false,
        retryAfterMillis: Long? = null,
    ) = ProtonThumbnailFollowUpPolicy.followUp(outcome, workRemaining, previewsDeferred, retryAfterMillis)
}
