package com.bownee.lenswave.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPlaybackPausePolicyTest {
    @Test
    fun `a video playing when the viewer leaves the screen is saved as playing`() {
        val pausedWhilePlaying = ViewerPlaybackPausePolicy.remembersPlaying(playWhenReady = true)

        assertTrue(pausedWhilePlaying)
        // The player was paused by onStop; the saved state still says playing.
        assertTrue(ViewerPlaybackPausePolicy.savedPlayWhenReady(playWhenReady = false, pausedWhilePlaying))
        assertTrue(ViewerPlaybackPausePolicy.resumesOnReturn(pausedWhilePlaying))
    }

    @Test
    fun `a video the user had paused stays paused through leaving and rotating`() {
        val pausedWhilePlaying = ViewerPlaybackPausePolicy.remembersPlaying(playWhenReady = false)

        assertFalse(pausedWhilePlaying)
        assertFalse(ViewerPlaybackPausePolicy.savedPlayWhenReady(playWhenReady = false, pausedWhilePlaying))
        assertFalse(ViewerPlaybackPausePolicy.resumesOnReturn(pausedWhilePlaying))
    }

    @Test
    fun `once back on screen the player's own state decides what a rotation restores`() {
        // Backgrounded while playing, returned (mark spent), then paused by the user: a rotation
        // must not start the video again.
        assertFalse(ViewerPlaybackPausePolicy.savedPlayWhenReady(playWhenReady = false, pausedWhilePlaying = false))
        assertTrue(ViewerPlaybackPausePolicy.savedPlayWhenReady(playWhenReady = true, pausedWhilePlaying = false))
    }
}
