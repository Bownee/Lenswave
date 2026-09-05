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

    @Test
    fun `a video that becomes ready while the viewer is stopped waits for onStart`() {
        // Ready after onStop: shown paused, and the deferred start is what onStart resumes.
        assertFalse(ViewerPlaybackPausePolicy.playsOnShow(intendedPlaying = true, started = false))
        val pausedWhilePlaying = ViewerPlaybackPausePolicy.defersStart(intendedPlaying = true, started = false)
        assertTrue(pausedWhilePlaying)
        assertTrue(ViewerPlaybackPausePolicy.savedPlayWhenReady(playWhenReady = false, pausedWhilePlaying))
        assertTrue(ViewerPlaybackPausePolicy.resumesOnReturn(pausedWhilePlaying))
    }

    @Test
    fun `a video shown on screen plays at once and defers nothing`() {
        assertTrue(ViewerPlaybackPausePolicy.playsOnShow(intendedPlaying = true, started = true))
        assertFalse(ViewerPlaybackPausePolicy.defersStart(intendedPlaying = true, started = true))
    }

    @Test
    fun `a restored paused video stays paused whether or not the viewer is on screen`() {
        assertFalse(ViewerPlaybackPausePolicy.playsOnShow(intendedPlaying = false, started = true))
        assertFalse(ViewerPlaybackPausePolicy.playsOnShow(intendedPlaying = false, started = false))
        assertFalse(ViewerPlaybackPausePolicy.defersStart(intendedPlaying = false, started = false))
    }
}
