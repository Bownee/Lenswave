package com.bownee.lenswave.viewer

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonOriginalDownloadProgress
import com.bownee.lenswave.proton.ProtonOriginalStream
import com.bownee.lenswave.proton.ProtonProgressiveDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Owns the ExoPlayer behind the viewer's [PhotoViewerScreen.playerView]: one player for the life of
 * the viewer, pointed at each cached or still-downloading original in turn, reporting download
 * progress until the first frame is ready, fading the picture in over the thumbnail, and stopping
 * playback when the viewer moves on. The Activity owns the current request and `photoReady`; this
 * controller reads them and asks for changes through [Host].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ViewerVideoController(
    private val context: Context,
    private val screen: PhotoViewerScreen,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    internal interface Host {
        val currentStableId: String

        /** True once the current media shows its full-quality picture. */
        val mediaReady: Boolean
        val detailsShown: Boolean

        /** Records the file the details sheet reads metadata from. */
        fun onMediaResolved(uri: Uri)

        fun ensureDetailsMetadataLoaded()

        /** Shows the loading panel right away instead of after the usual delay. */
        fun showLoadingPanelImmediately()

        /** Hides the loading panel and withdraws any delayed show of it. */
        fun hideLoadingPanel()

        /** The first frame is decoded: marks the media ready and re-enables the actions. */
        fun onVideoReady(requestedStableId: String)

        fun clearThumbnailPreview()

        /** Loads the current media from scratch; for a complete original whose plaintext copy is gone. */
        fun reloadMedia()

        fun handleLoadFailure(
            error: Throwable,
            fallbackMessage: String,
        )
    }

    private val photoView get() = screen.photoView
    private val playerView get() = screen.playerView
    private val thumbnailPreview get() = screen.thumbnailPreview
    private val status get() = screen.status
    private val progress get() = screen.progress
    private val retryButton get() = screen.retryButton
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var pendingPreviewClear: Runnable? = null

    /** The last progress of the download behind the current media; null for a media fully on disk. */
    private var latestProgress: ProtonOriginalDownloadProgress? = null

    /** True while the player reports STATE_BUFFERING for the current media. */
    private var buffering = false

    /** True while a reader of the current download is blocked at bytes that have not arrived. */
    private var waitingForBytes = false

    /** Applied by the next [show], so a recreated viewer resumes where the user was. */
    private var pendingPlayback: ViewerPlaybackState? = null

    /**
     * True when [pause] stopped a playing video, or a video was shown while the viewer was
     * stopped; the saved state should still say "playing" and [resume] starts it.
     */
    private var pausedWhilePlaying = false

    /** True between the Activity's onStart and onStop; a video shown outside that window does not start. */
    private var started = false

    /** The media the player was last asked to show; null once [stop] has cleared it. */
    private var activeStableId: String? = null

    /** True while every byte of the current media is on disk: a cached original, or a download that completed. */
    private var streamComplete = false

    /**
     * The media a vanished copy was reloaded for, so it is reloaded once; cleared when a
     * different media is shown (see [ViewerVideoReloadPolicy]).
     */
    private var reloadedStableId: String? = null

    /**
     * Counts every [show]; the current value tags the media item so an event the player queued for
     * an earlier item is recognised after a `stop(); show(other)` pair, where the stable-id guards
     * alone would pass.
     */
    private var showGeneration = 0L

    /** The current media's picture size, once the decoder has reported it; null before and between media. */
    private var videoSize: VideoSize? = null

    init {
        // The framing depends on the box as much as on the clip: the insets and a rotation
        // change the box after the clip's size is known.
        playerView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyFraming()
        }
    }

    private val playerListener =
        object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                val activePlayer = player ?: return
                if (activeStableId == null || !isCurrentShow(activePlayer)) return
                videoSize = size
                applyFraming()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val requestedStableId = activeStableId ?: return
                if (host.currentStableId != requestedStableId) return
                // An event delivered late for a previous item: the player has since been given a
                // new one, whose tag differs and which is buffering again.
                val activePlayer = player ?: return
                if (!isCurrentShow(activePlayer) || activePlayer.playbackState != playbackState) return
                buffering = playbackState == Player.STATE_BUFFERING
                if (host.mediaReady) {
                    // A seek past the downloaded bytes parks the player on them; the download's
                    // progress comes back over the picture so the wait is not a silent freeze.
                    refreshProgressPanel(requestedStableId)
                    return
                }
                if (playbackState != Player.STATE_READY) return
                // The collector stays on while the download is in flight; the panel it feeds is
                // hidden by onVideoReady and returns only for a buffering stall.
                host.onVideoReady(requestedStableId)
                // No fade: the stand-in is framed exactly as the first frame, so the picture
                // swaps in place, and the player's surface must never sit under a partial
                // alpha. Below one the view renders through a layer, and on alternate frames
                // the part of the surface the box crops escaped it and flashed above and
                // below the box for the length of the fade. The stand-in stays a moment
                // longer so the frame is on the display before it goes.
                playerView.animate().cancel()
                playerView.alpha = 1f
                if (thumbnailPreview.isVisible) {
                    thumbnailPreview.animate().cancel()
                    cancelPendingPreviewClear()
                    val clear =
                        Runnable {
                            pendingPreviewClear = null
                            if (host.currentStableId == requestedStableId) host.clearThumbnailPreview()
                        }
                    pendingPreviewClear = clear
                    thumbnailPreview.postDelayed(clear, STAND_IN_LINGER_MILLIS)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.VIDEO_PLAYBACK, error)
                val requestedStableId = activeStableId ?: return
                if (host.currentStableId != requestedStableId) return
                // A stale error: prepare() for a newer item has already cleared it from the player.
                val activePlayer = player ?: return
                if (!isCurrentShow(activePlayer) || activePlayer.playerError !== error) return
                // A complete original whose plaintext copy the TTL sweep took during a long
                // pause: loading again decrypts a fresh copy, once.
                if (ViewerVideoReloadPolicy.reloads(error, streamComplete, reloadedStableId == requestedStableId)) {
                    reloadedStableId = requestedStableId
                    host.reloadMedia()
                    return
                }
                host.handleLoadFailure(error, context.getString(R.string.could_not_play_video))
            }
        }

    private fun isCurrentShow(activePlayer: ExoPlayer): Boolean =
        activePlayer.currentMediaItem?.localConfiguration?.tag == showGeneration

    /** Plays an original that is already fully on disk. */
    fun show(uri: Uri) = show(uri, dataSourceFactory = null)

    /** Plays an original while Proton is still appending bytes to [stream], showing its progress. */
    fun showProgressive(
        stream: ProtonOriginalStream,
        requestedStableId: String,
    ) {
        show(Uri.fromFile(stream.file), ProtonProgressiveDataSource.Factory(stream))
        host.showLoadingPanelImmediately()
        progressJob =
            scope.launch {
                // A reader parked on bytes still to come is a stall the player may not report
                // as buffering yet; the panel comes back for it the same way. Both collectors
                // end together when the download completes, when neither can change again.
                launch {
                    stream.waitingForBytes.collect { waiting ->
                        if (host.currentStableId != requestedStableId) return@collect
                        waitingForBytes = waiting
                        refreshProgressPanel(requestedStableId)
                    }
                }
                stream.progress.collect { downloadProgress ->
                    if (host.currentStableId != requestedStableId) return@collect
                    latestProgress = downloadProgress
                    streamComplete = downloadProgress.complete
                    refreshProgressPanel(requestedStableId)
                    if (!ViewerVideoProgressPolicy.keepObserving(downloadProgress.complete)) {
                        progressJob = null
                        waitingForBytes = false
                        cancel()
                    }
                }
            }
    }

    /**
     * Shows, updates or hides the progress panel for the current download according to
     * [ViewerVideoProgressPolicy.panelVisible]. Before the first frame the panel is already up
     * and only its figures change; afterwards it appears for a buffering stall against an
     * unfinished download and goes when the player moves on or the download completes.
     */
    private fun refreshProgressPanel(requestedStableId: String) {
        val downloadProgress = latestProgress ?: return
        if (host.currentStableId != requestedStableId) return
        val mediaReady = host.mediaReady
        if (ViewerVideoProgressPolicy.panelVisible(mediaReady, buffering, waitingForBytes, downloadProgress.complete)) {
            if (mediaReady) host.showLoadingPanelImmediately()
            updateDownloadProgress(downloadProgress)
        } else if (mediaReady) {
            host.hideLoadingPanel()
        }
    }

    /**
     * Pauses for the viewer leaving the screen; call from the Activity's onStop. A video that
     * becomes ready after this, from a download still in flight, is shown paused (see [show]).
     */
    fun pause() {
        started = false
        val active = player ?: return
        if (ViewerPlaybackPausePolicy.remembersPlaying(active.playWhenReady)) pausedWhilePlaying = true
        active.pause()
    }

    /**
     * The viewer is back on screen: a video [pause] stopped, or one shown while stopped, goes on
     * playing, and the mark is spent either way. Left set, it would make a rotation start a
     * video the user had since paused. Call from the Activity's onStart.
     */
    fun resume() {
        started = true
        val resumes = ViewerPlaybackPausePolicy.resumesOnReturn(pausedWhilePlaying)
        pausedWhilePlaying = false
        if (resumes) player?.play()
    }

    /**
     * Where the video on screen is and whether it is playing; null when no video is up. On this
     * platform the state is saved after onStop, so a video [pause]d there still reports playing.
     */
    fun playbackState(): ViewerPlaybackState? {
        val active = player ?: return null
        if (activeStableId == null) return null
        return ViewerPlaybackState(
            positionMillis = active.currentPosition,
            playWhenReady = ViewerPlaybackPausePolicy.savedPlayWhenReady(active.playWhenReady, pausedWhilePlaying),
        )
    }

    /** Applies [state] to the next [show]: the player seeks there before preparing, so the first frame is that one. */
    fun restorePlayback(state: ViewerPlaybackState) {
        pendingPlayback = state
    }

    /** Drops a restored state that was never applied, e.g. when the user swiped on before the video loaded. */
    fun discardPendingPlayback() {
        pendingPlayback = null
    }

    /**
     * Stops playback and drops the current media so the viewer can move on to a photo or another
     * video. The player itself survives: building an ExoPlayer for every item, including the
     * photos between videos, cost far more than resetting one.
     */
    fun stop() {
        progressJob?.cancel()
        progressJob = null
        latestProgress = null
        buffering = false
        waitingForBytes = false
        streamComplete = false
        activeStableId = null
        pausedWhilePlaying = false
        videoSize = null
        applyFraming()
        player?.let { active ->
            active.stop()
            active.clearMediaItems()
        }
    }

    /**
     * Frames the current clip in the player's box per [ViewerVideoFramingPolicy]: spanning the
     * width, and cropped by the box if taller, unless that would cut off too much of it. Without
     * a clip the box goes back to fitting, so the next clip's first frame is never cropped by
     * the framing of the last.
     */
    private fun applyFraming() {
        val size = videoSize
        val framing =
            if (size == null) {
                ViewerVideoFramingPolicy.Framing.FIT
            } else {
                ViewerVideoFramingPolicy.framing(
                    videoWidth = (size.width * size.pixelWidthHeightRatio).roundToInt(),
                    videoHeight = size.height,
                    boxWidth = playerView.width - playerView.paddingLeft - playerView.paddingRight,
                    boxHeight = playerView.height - playerView.paddingTop - playerView.paddingBottom,
                )
            }
        val resizeMode =
            when (framing) {
                ViewerVideoFramingPolicy.Framing.FILL_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                ViewerVideoFramingPolicy.Framing.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        if (playerView.resizeMode != resizeMode) playerView.resizeMode = resizeMode
    }

    /** Releases the player for good; call from the Activity's onDestroy. */
    fun release() {
        stop()
        playerView.player = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    /** Drops a thumbnail clear scheduled after the first frame's fade-in. */
    fun cancelPendingPreviewClear() {
        pendingPreviewClear?.let(thumbnailPreview::removeCallbacks)
        pendingPreviewClear = null
    }

    private fun show(
        uri: Uri,
        dataSourceFactory: ProtonProgressiveDataSource.Factory?,
    ) {
        val requestedStableId = host.currentStableId
        host.onMediaResolved(uri)
        if (host.detailsShown && dataSourceFactory == null) host.ensureDetailsMetadataLoaded()
        photoView.clear()
        photoView.visibility = View.GONE
        stop()
        playerView.visibility = View.VISIBLE
        playerView.alpha = 0f
        val activePlayer = ensurePlayer()
        activeStableId = requestedStableId
        // A file on disk is complete from the start; a progressive stream reports it later.
        streamComplete = dataSourceFactory == null
        if (reloadedStableId != requestedStableId) reloadedStableId = null
        showGeneration++
        val mediaItem =
            MediaItem
                .Builder()
                .setUri(uri)
                .setTag(showGeneration)
                .build()
        if (dataSourceFactory == null) {
            activePlayer.setMediaItem(mediaItem)
        } else {
            activePlayer.setMediaSource(
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem),
            )
        }
        val restored = pendingPlayback
        pendingPlayback = null
        if (restored != null) activePlayer.seekTo(restored.positionMillis)
        activePlayer.prepare()
        // Off screen there is nothing to pause yet, so the start itself waits for resume():
        // a progressive download that readies after onStop must not play in the background.
        val intendedPlaying = restored?.playWhenReady ?: true
        activePlayer.playWhenReady = ViewerPlaybackPausePolicy.playsOnShow(intendedPlaying, started)
        pausedWhilePlaying = ViewerPlaybackPausePolicy.defersStart(intendedPlaying, started)
    }

    private fun ensurePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(context).build().also { created ->
            created.addListener(playerListener)
            player = created
            playerView.player = created
        }

    /**
     * The download shows as the bar alone: the byte counts and percentage it used to spell out
     * sat in the middle of the picture, over the thumbnail, and said nothing the bar does not.
     */
    private fun updateDownloadProgress(downloadProgress: ProtonOriginalDownloadProgress) {
        progress.visibility = View.VISIBLE
        status.visibility = View.GONE
        retryButton.visibility = View.GONE
        when (val display = ViewerVideoProgressPolicy.display(downloadProgress)) {
            ViewerVideoProgressPolicy.Display.Preparing -> {
                progress.isIndeterminate = false
                progress.max = ViewerVideoProgressPolicy.PROGRESS_MAX
                progress.progress = ViewerVideoProgressPolicy.PROGRESS_MAX
            }

            is ViewerVideoProgressPolicy.Display.Unsized -> {
                progress.isIndeterminate = true
            }

            is ViewerVideoProgressPolicy.Display.Sized -> {
                progress.isIndeterminate = false
                progress.max = ViewerVideoProgressPolicy.PROGRESS_MAX
                progress.progress = display.progress
            }
        }
    }

    private companion object {
        /** How long the stand-in stays under the first frame before it is cleared. */
        const val STAND_IN_LINGER_MILLIS = 180L
    }
}

/** Playback position and state of the video on screen, carried across the viewer's recreation. */
internal data class ViewerPlaybackState(
    val positionMillis: Long,
    val playWhenReady: Boolean,
)
