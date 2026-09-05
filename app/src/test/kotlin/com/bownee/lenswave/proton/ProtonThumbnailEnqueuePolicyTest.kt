package com.bownee.lenswave.proton

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailEnqueuePolicyTest {
    @Test
    fun `a run that is queued or going answers the ask`() {
        assertFalse(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(activeRun = true, lastEnqueuedAtMillis = null, nowMillis = 0L),
        )
        assertFalse(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                activeRun = true,
                lastEnqueuedAtMillis = 0L,
                nowMillis = 1_000_000L,
            ),
        )
    }

    @Test
    fun `the first ask goes through and the next waits out the debounce`() {
        val debounce = ProtonThumbnailEnqueuePolicy.DEBOUNCE_MILLIS

        assertTrue(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(activeRun = false, lastEnqueuedAtMillis = null, nowMillis = 5L),
        )
        assertFalse(ProtonThumbnailEnqueuePolicy.shouldEnqueue(false, lastEnqueuedAtMillis = 5L, nowMillis = 5L))
        assertFalse(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                false,
                lastEnqueuedAtMillis = 5L,
                nowMillis =
                    5L + debounce - 1,
            ),
        )
        assertTrue(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                false,
                lastEnqueuedAtMillis = 5L,
                nowMillis =
                    5L + debounce,
            ),
        )
    }

    @Test
    fun `a clock that went backwards does not silence the ask`() {
        assertTrue(ProtonThumbnailEnqueuePolicy.shouldEnqueue(false, lastEnqueuedAtMillis = 9_000L, nowMillis = 1_000L))
    }

    @Test
    fun `a follow-up waiting for the charger is the one active run an ask replaces`() {
        val waiting = ProtonThumbnailQueuedRequest(WorkInfo.State.ENQUEUED, requiresCharging = true)
        val done = ProtonThumbnailQueuedRequest(WorkInfo.State.SUCCEEDED, requiresCharging = false)

        assertTrue(ProtonThumbnailEnqueuePolicy.isWaitingForCharger(listOf(done, waiting)))
        assertTrue(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                activeRun = true,
                lastEnqueuedAtMillis = null,
                nowMillis = 0L,
                waitingForCharger = true,
            ),
        )
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            ProtonThumbnailEnqueuePolicy.existingWorkPolicy(waitingForCharger = true),
        )
        assertEquals(
            ExistingWorkPolicy.KEEP,
            ProtonThumbnailEnqueuePolicy.existingWorkPolicy(waitingForCharger = false),
        )
    }

    @Test
    fun `the debounce still applies to an ask that would replace the charging wait`() {
        assertFalse(
            ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                activeRun = true,
                lastEnqueuedAtMillis = 5L,
                nowMillis = 5L + ProtonThumbnailEnqueuePolicy.DEBOUNCE_MILLIS - 1,
                waitingForCharger = true,
            ),
        )
    }

    @Test
    fun `a running follow-up, a plain queued run or an empty name are not a charging wait`() {
        val runningOnCharger = ProtonThumbnailQueuedRequest(WorkInfo.State.RUNNING, requiresCharging = true)
        val waiting = ProtonThumbnailQueuedRequest(WorkInfo.State.ENQUEUED, requiresCharging = true)
        val plain = ProtonThumbnailQueuedRequest(WorkInfo.State.ENQUEUED, requiresCharging = false)
        val running = ProtonThumbnailQueuedRequest(WorkInfo.State.RUNNING, requiresCharging = false)
        val chained = ProtonThumbnailQueuedRequest(WorkInfo.State.BLOCKED, requiresCharging = true)

        assertFalse(ProtonThumbnailEnqueuePolicy.isWaitingForCharger(listOf(runningOnCharger)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isWaitingForCharger(listOf(plain)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isWaitingForCharger(listOf(running, chained)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isWaitingForCharger(listOf(waiting, plain)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isWaitingForCharger(emptyList()))
        assertFalse(
            ProtonThumbnailEnqueuePolicy.isWaitingForCharger(
                listOf(ProtonThumbnailQueuedRequest(WorkInfo.State.CANCELLED, requiresCharging = true)),
            ),
        )
    }

    @Test
    fun `queued, blocked and running requests are active, finished ones are not`() {
        assertTrue(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.BLOCKED)))
        assertTrue(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.RUNNING)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isActive(emptyList()))
    }
}
