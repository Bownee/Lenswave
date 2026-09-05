package com.bownee.lenswave.viewer

/**
 * How a video's "playing" state survives the viewer leaving the screen. The Activity pauses the
 * player in onStop, but its state is saved after onStop, so a video playing when the user left
 * must still be saved as playing. The mark only lives until the viewer is back on screen: from
 * then on the player's own state is the truth, and a rotation of a video the user has since
 * paused must not start it again.
 *
 * The same mark covers a video that becomes ready while the viewer is stopped: a progressive
 * download that finishes after onStop has no player to pause yet, so it is shown paused and the
 * mark makes onStart play it, instead of audio starting in the background.
 */
internal object ViewerPlaybackPausePolicy {
    /** Whether a pause forced by leaving the screen should be remembered as "was playing". */
    fun remembersPlaying(playWhenReady: Boolean): Boolean = playWhenReady

    /** The play state to save: the player's own, or the remembered one while the viewer is off screen. */
    fun savedPlayWhenReady(
        playWhenReady: Boolean,
        pausedWhilePlaying: Boolean,
    ): Boolean = playWhenReady || pausedWhilePlaying

    /** Whether coming back on screen should start the video again; the mark is spent either way. */
    fun resumesOnReturn(pausedWhilePlaying: Boolean): Boolean = pausedWhilePlaying

    /** Whether a video shown now starts at once: only while the viewer is on screen. */
    fun playsOnShow(
        intendedPlaying: Boolean,
        started: Boolean,
    ): Boolean = intendedPlaying && started

    /** Whether a video shown while the viewer is stopped keeps its start for [resumesOnReturn]. */
    fun defersStart(
        intendedPlaying: Boolean,
        started: Boolean,
    ): Boolean = intendedPlaying && !started
}
