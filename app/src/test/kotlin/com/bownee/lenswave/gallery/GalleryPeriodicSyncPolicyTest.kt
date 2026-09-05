package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonSyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

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

    @Test
    fun `the interval is well above the freshness limit so ticks rarely re-enumerate`() {
        val freshnessLimit = ProtonSyncSource.TIMELINE.maximumAgeMillis

        assertTrue(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS >= freshnessLimit * 3)
        assertTrue(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS >= Duration.ofMinutes(45).toMillis())
        // A tick that lands within the freshness limit of a user-driven refresh does nothing; the
        // interval must leave room for that rather than aligning with the limit.
        assertEquals(0L, GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS % freshnessLimit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the interval must be positive`() {
        GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(null, 0L, intervalMillis = 0L)
    }

    @Test
    fun `a check refreshes only once the last refresh is a freshness limit old`() {
        assertTrue(GalleryPeriodicSyncPolicy.shouldRefresh(lastRefreshMillis = null, nowMillis = 5_000L))
        assertFalse(
            GalleryPeriodicSyncPolicy.shouldRefresh(
                lastRefreshMillis = 1_000L,
                nowMillis = 1_999L,
                freshnessLimitMillis = 1_000L,
            ),
        )
        assertTrue(
            GalleryPeriodicSyncPolicy.shouldRefresh(
                lastRefreshMillis = 1_000L,
                nowMillis = 2_000L,
                freshnessLimitMillis = 1_000L,
            ),
        )
        assertTrue(
            "a clock that went backwards does not postpone the refresh",
            GalleryPeriodicSyncPolicy.shouldRefresh(
                lastRefreshMillis = 5_000L,
                nowMillis = 1_000L,
                freshnessLimitMillis = 1_000L,
            ),
        )
        assertEquals(ProtonSyncSource.TIMELINE.maximumAgeMillis, GalleryPeriodicSyncPolicy.FRESHNESS_LIMIT_MILLIS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the freshness limit must be positive`() {
        GalleryPeriodicSyncPolicy.shouldRefresh(null, 0L, freshnessLimitMillis = 0L)
    }
}
