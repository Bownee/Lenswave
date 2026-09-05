package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailFollowUpPolicyTest {
    @Test
    fun `a lost network is followed up after a backoff that grows per consecutive wait`() {
        val base = ProtonThumbnailFollowUpPolicy.NETWORK_WAIT_BASE_DELAY_MILLIS

        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = base, networkWaitAttempt = 1),
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, workRemaining = true),
        )
        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = base * 4, networkWaitAttempt = 3),
            followUp(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, workRemaining = true, networkWaitAttempt = 2),
        )
    }

    @Test
    fun `the network backoff ladder doubles up to its cap`() {
        val base = ProtonThumbnailFollowUpPolicy.NETWORK_WAIT_BASE_DELAY_MILLIS
        val max = ProtonThumbnailFollowUpPolicy.MAX_NETWORK_WAIT_DELAY_MILLIS

        assertEquals(base, ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(0))
        assertEquals(base * 2, ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(1))
        assertEquals(base * 16, ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(4))
        assertEquals(max, ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(5))
        assertEquals(max, ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(40))
        assertEquals(max, ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(Int.MAX_VALUE))
        assertTrue(max >= 30L * 60L * 1_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a network wait attempt cannot be negative`() {
        ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a follow-up cannot carry a negative network wait attempt`() {
        ProtonThumbnailFollowUp(requiresCharging = false, networkWaitAttempt = -1)
    }

    @Test
    fun `a run that hit its limit pauses before the next one while work remains`() {
        assertEquals(
            ProtonThumbnailFollowUp(
                requiresCharging = false,
                initialDelayMillis = ProtonThumbnailFollowUpPolicy.RUN_LIMIT_DELAY_MILLIS,
            ),
            followUp(ProtonThumbnailWorkOutcome.TIMED_OUT, workRemaining = true, retryAfterMillis = 60_000L),
        )
        assertTrue(ProtonThumbnailFollowUpPolicy.RUN_LIMIT_DELAY_MILLIS > 0L)
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

    @Test
    fun `a refused foreground promotion is followed up after a long pause`() {
        assertEquals(
            ProtonThumbnailFollowUp(
                requiresCharging = false,
                initialDelayMillis = ProtonThumbnailForegroundBudgetPolicy.FOREGROUND_REFUSED_DELAY_MILLIS,
            ),
            followUp(ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE, workRemaining = true),
        )
        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = true),
            followUp(ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE, previewsDeferred = true),
        )
        assertNull(followUp(ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE))
    }

    @Test
    fun `the foreground budget holds every follow-up back at least as long as it says`() {
        val budget = 5L * 60L * 60L * 1_000L

        assertEquals(
            budget,
            followUp(ProtonThumbnailWorkOutcome.TIMED_OUT, workRemaining = true, foregroundBudgetDelayMillis = budget)
                ?.initialDelayMillis,
        )
        assertEquals(
            budget,
            followUp(
                ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY,
                workRemaining = true,
                retryAfterMillis = 30_000L,
                foregroundBudgetDelayMillis = budget,
            )?.initialDelayMillis,
        )
        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = true, initialDelayMillis = budget),
            followUp(
                ProtonThumbnailWorkOutcome.COMPLETE,
                previewsDeferred = true,
                foregroundBudgetDelayMillis = budget,
            ),
        )
        // A longer backoff of its own is kept, and the ladder position survives the floor.
        val max = ProtonThumbnailFollowUpPolicy.MAX_NETWORK_WAIT_DELAY_MILLIS
        assertEquals(
            ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = max, networkWaitAttempt = 9),
            followUp(
                ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK,
                workRemaining = true,
                networkWaitAttempt = 8,
                foregroundBudgetDelayMillis = 1_000L,
            ),
        )
        assertNull(
            followUp(ProtonThumbnailWorkOutcome.PAUSED, workRemaining = true, foregroundBudgetDelayMillis = budget),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a budget delay cannot be negative`() {
        followUp(ProtonThumbnailWorkOutcome.COMPLETE, foregroundBudgetDelayMillis = -1L)
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
        networkWaitAttempt: Int = 0,
        foregroundBudgetDelayMillis: Long = 0L,
    ) = ProtonThumbnailFollowUpPolicy.followUp(
        outcome,
        workRemaining,
        previewsDeferred,
        retryAfterMillis,
        networkWaitAttempt,
        foregroundBudgetDelayMillis,
    )
}
