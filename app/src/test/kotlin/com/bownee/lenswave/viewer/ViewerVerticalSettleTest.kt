package com.bownee.lenswave.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerVerticalSettleTest {
    @Test
    fun nothingLeftToTravelTakesNoTime() {
        assertEquals(0L, ViewerVerticalSettle.duration(0.5f, 3_000f, 1f))
        assertEquals(0L, ViewerVerticalSettle.duration(-0.99f, 0f, 1f))
    }

    @Test
    fun slowReleaseUsesTheDefaultDuration() {
        assertEquals(260L, ViewerVerticalSettle.duration(400f, 0f, 1f))
        assertEquals(260L, ViewerVerticalSettle.duration(400f, 199f, 1f))
        // The velocity floor is 200dp: at 3x density a 500px/s release is still "slow".
        assertEquals(260L, ViewerVerticalSettle.duration(400f, 500f, 3f))
    }

    @Test
    fun flingDurationFollowsRemainingDistanceOverVelocity() {
        // 400px at 1600px/s: 0.25s * 800 = 200ms.
        assertEquals(200L, ViewerVerticalSettle.duration(400f, 1_600f, 1f))
        assertEquals(200L, ViewerVerticalSettle.duration(-400f, -1_600f, 1f))
    }

    @Test
    fun flingDurationIsClampedBetweenTheFloorAndTheDefault() {
        assertEquals(140L, ViewerVerticalSettle.duration(10f, 5_000f, 1f))
        assertEquals(260L, ViewerVerticalSettle.duration(2_000f, 600f, 1f))
    }
}
