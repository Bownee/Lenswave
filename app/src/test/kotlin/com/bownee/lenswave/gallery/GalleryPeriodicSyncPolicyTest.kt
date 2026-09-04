package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryPeriodicSyncPolicyTest {
    @Test
    fun `first check runs immediately`() {
        assertEquals(
            0L,
            GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(lastCheckMillis = null, nowMillis = 5_000L),
        )
    }

    @Test
    fun `next check waits for the rest of the interval`() {
        assertEquals(
            700L,
            GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(
                lastCheckMillis = 1_000L,
                nowMillis = 1_300L,
                intervalMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `an overdue check runs at once`() {
        assertEquals(
            0L,
            GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(
                lastCheckMillis = 1_000L,
                nowMillis = 9_000L,
                intervalMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `a clock that went backwards does not postpone the check`() {
        assertEquals(
            0L,
            GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(
                lastCheckMillis = 5_000L,
                nowMillis = 1_000L,
                intervalMillis = 1_000L,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the interval must be positive`() {
        GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(null, 0L, intervalMillis = 0L)
    }
}
