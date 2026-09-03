package com.bownee.lenswave.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalGesturePolicyTest {
    @Test
    fun detailsSurfaceCanMoveAboveItsInitialPosition() {
        assertEquals(700, VerticalGesturePolicy.detailsSettleOffset(700, 0f, 500, 1_000))
        assertEquals(820, VerticalGesturePolicy.detailsSettleOffset(700, 1_000f, 500, 1_000))
    }

    @Test
    fun detailsSurfaceReturnsToHiddenPositionAfterDownwardDrag() {
        assertEquals(0, VerticalGesturePolicy.detailsSettleOffset(180, 0f, 500, 1_000))
    }

    @Test
    fun sheetSettlesAfterDeliberateDistanceOrFling() {
        assertTrue(VerticalGesturePolicy.shouldSettleSheet(220f, 0f, 900f, 1f))
        assertTrue(VerticalGesturePolicy.shouldSettleSheet(50f, 1_000f, 900f, 1f))
        assertFalse(VerticalGesturePolicy.shouldSettleSheet(40f, 1_500f, 900f, 1f))
    }

    @Test
    fun viewerDismissRequiresDistanceEvenWithVelocity() {
        assertTrue(VerticalGesturePolicy.shouldDismissViewer(110f, 0f, 900f, 1f))
        assertTrue(VerticalGesturePolicy.shouldDismissViewer(44f, 950f, 900f, 1f))
        assertFalse(VerticalGesturePolicy.shouldDismissViewer(36f, 2_000f, 900f, 1f))
    }
}
