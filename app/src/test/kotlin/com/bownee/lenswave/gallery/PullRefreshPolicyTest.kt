package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRefreshPolicyTest {
    @Test
    fun `downward pull from the top refreshes`() {
        assertTrue(PullRefreshPolicy.shouldRefresh(true, false, 8f, 186f, 180f))
    }

    @Test
    fun `short horizontal or non-top gestures do not refresh`() {
        assertFalse(PullRefreshPolicy.shouldRefresh(true, false, 4f, 170f, 180f))
        assertFalse(PullRefreshPolicy.shouldRefresh(true, false, 190f, 186f, 180f))
        assertFalse(PullRefreshPolicy.shouldRefresh(false, false, 0f, 210f, 180f))
    }

    @Test
    fun `dragging the fast scroll thumb does not refresh`() {
        assertFalse(PullRefreshPolicy.shouldRefresh(true, true, 0f, 210f, 180f))
    }

    @Test
    fun `pull progress is visible and capped at the refresh threshold`() {
        assertEquals(0f, PullRefreshPolicy.progress(0f, 180f), 0.001f)
        assertEquals(0.5f, PullRefreshPolicy.progress(90f, 180f), 0.001f)
        assertEquals(1f, PullRefreshPolicy.progress(210f, 180f), 0.001f)
    }
}
