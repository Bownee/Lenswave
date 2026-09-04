package com.bownee.lenswave.proton

import androidx.work.WorkInfo
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
    fun `queued, blocked and running requests are active, finished ones are not`() {
        assertTrue(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.BLOCKED)))
        assertTrue(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.RUNNING)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isActive(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)))
        assertFalse(ProtonThumbnailEnqueuePolicy.isActive(emptyList()))
    }
}
