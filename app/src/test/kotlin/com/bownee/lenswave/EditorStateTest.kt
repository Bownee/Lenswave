package com.bownee.lenswave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorStateTest {
    @Test
    fun manualAdjustmentRetainsActiveLook() {
        val vivid = EditorState(PhotoAdjustments.NEUTRAL, 1)
        val brighter = vivid.adjustments.withValue(PhotoAdjustments.BRIGHTNESS, 0.2f)
        val adjusted = vivid.withAdjustments(brighter)
        assertEquals(1, adjusted.activeLook)
        assertEquals(0.2f, adjusted.adjustments.brightness, 0f)
    }

    @Test
    fun activeLookParticipatesInStateEquality() {
        val auto = EditorState(PhotoAdjustments.NEUTRAL, 0)
        val vivid = EditorState(PhotoAdjustments.NEUTRAL, 1)
        assertNotEquals(auto, vivid)
    }
}
