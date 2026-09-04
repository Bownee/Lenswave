package com.bownee.lenswave.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.R
import com.bownee.lenswave.UiStyle
import com.bownee.lenswave.applyBottomOverlayInsets
import com.bownee.lenswave.configureEdgeToEdgeWindow
import com.bownee.lenswave.dp
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.MediaKind
import com.bownee.lenswave.gallery.PhotoDeletionDecision
import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.PhotoDeletionPolicy
import com.bownee.lenswave.gallery.ProtonOriginalMediaSource
import com.bownee.lenswave.gallery.ProtonPhotoMutations
import com.bownee.lenswave.gallery.ProtonThumbnailImageSource
import com.bownee.lenswave.metadata.PhotoMetadataReader
import com.bownee.lenswave.proton.ProtonOriginalDownloadProgress
import com.bownee.lenswave.proton.ProtonOriginalStream
import com.bownee.lenswave.proton.ProtonProgressiveDataSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows one photo or video from the gallery. The Activity owns the current [request], the
 * navigation list and the `photoTransitioning`/`dismissing` flags, and handles loading, favourites
 * and deletion itself; gestures are delegated to [ViewerSwipeController],
 * [ViewerDismissController] and [ViewerDetailsSheetController].
 */
@AndroidEntryPoint
class PhotoViewerActivity : FragmentActivity() {
    @Inject lateinit var originalMedia: ProtonOriginalMediaSource

    @Inject lateinit var thumbnailSource: ProtonThumbnailImageSource

    @Inject lateinit var photoMutations: ProtonPhotoMutations

    @Inject lateinit var metadataReader: PhotoMetadataReader

    @Inject lateinit var photoDeletionExecutor: PhotoDeletionExecutor

    private lateinit var screen: PhotoViewerScreen
    private val root get() = screen.root
    private val photoDetailsScroll get() = screen.photoDetailsScroll
    private val mediaFrame get() = screen.mediaFrame
    private val thumbnailPreview get() = screen.thumbnailPreview
    private val peekPreview get() = screen.peekPreview
    private val photoView get() = screen.photoView
    private val playerView get() = screen.playerView
    private val loadingPanel get() = screen.loadingPanel
    private val status get() = screen.status
    private val progress get() = screen.progress
    private val retryButton get() = screen.retryButton
    private val mediaTitle get() = screen.mediaTitle
    private val actions get() = screen.actions
    private val favoriteButton get() = screen.favoriteButton
    private val deleteButton get() = screen.deleteButton
    private val detailsSheet get() = screen.detailsSheet
    private lateinit var mediaTransform: ViewerMediaTransform
    private lateinit var details: ViewerDetailsSheetController
    private lateinit var dismiss: ViewerDismissController
    private lateinit var swipe: ViewerSwipeController
    private lateinit var request: PhotoRequest
    private var navigationRequests: List<PhotoRequest> = emptyList()
    private var resolvedUri: Uri? = null
    private var photoTransitioning = false
    private var deletionInProgress = false
    private var favoriteInProgress = false
    private var dismissing = false
    private var photoReady = false
    private var previewStableId: String? = null
    private var navigationFallback: NavigationFallback? = null
    private var photoLoadJob: Job? = null
    private var videoProgressJob: Job? = null
    private var loadingPanelRunnable: Runnable? = null
    private var pendingPreviewClear: Runnable? = null

    /** Accumulates what the gallery must refresh; a later result must not drop an earlier flag. */
    private val resultIntent = Intent()
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PhotoRequest.from(intent)
        navigationRequests = PhotoRequest.navigationFrom(intent).ifEmpty { listOf(request) }
        configureEdgeToEdgeWindow()
        buildInterface()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )
        loadPhoto()
    }

    override fun onDestroy() {
        photoLoadJob?.cancel()
        details.release()
        cancelLoadingPanelDelay()
        clearThumbnailPreview()
        swipe.release()
        photoView.close()
        releasePlayer()
        super.onDestroy()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    private fun buildInterface() {
        screen =
            PhotoViewerScreen(
                context = this,
                callbacks =
                    PhotoViewerScreen.Actions(
                        gesturesEnabled = {
                            this::screen.isInitialized &&
                                !photoTransitioning &&
                                !dismissing &&
                                (request.mediaKind == MediaKind.VIDEO || photoView.isAtFitScale())
                        },
                        gestureStartAllowed = { _, y ->
                            request.mediaKind != MediaKind.VIDEO ||
                                playerView.height <= 0 ||
                                y < mediaFrame.top - photoDetailsScroll.scrollY + playerView.bottom -
                                dp(VIDEO_CONTROLS_HEIGHT_DP)
                        },
                        onVerticalDrag = { distance, velocity, finished ->
                            details.handleDetailsDrag(distance, velocity, finished)
                        },
                        onHorizontalDrag = { distance, finished ->
                            swipe.handleHorizontalPhotoDrag(distance, finished)
                        },
                        onFavorite = ::toggleFavorite,
                        onDelete = ::deletePhoto,
                        onRetry = ::loadPhoto,
                        onLayoutChanged = ::updatePhotoDetailsLayout,
                    ),
            )
        buildCollaborators()
        setContentView(screen.root)
        actions.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) updateMediaBounds()
        }
        applySystemInsets()
    }

    private fun buildCollaborators() {
        mediaTransform =
            ViewerMediaTransform(
                photoView = photoView,
                playerView = playerView,
                thumbnailPreview = thumbnailPreview,
                loadingPanel = loadingPanel,
                mediaTitle = mediaTitle,
            )
        dismiss =
            ViewerDismissController(
                activity = this,
                screen = screen,
                mediaTransform = mediaTransform,
                host =
                    object : ViewerDismissController.Host {
                        override val gesturesBlocked: Boolean get() = photoTransitioning || dismissing
                        override val dismissing: Boolean get() = this@PhotoViewerActivity.dismissing
                        override val detailsShown: Boolean get() = details.shown

                        override fun activeMediaView(): View = this@PhotoViewerActivity.activeMediaView()

                        override fun beginDismiss() {
                            this@PhotoViewerActivity.dismissing = true
                            setActionsEnabled(false)
                        }
                    },
            )
        details =
            ViewerDetailsSheetController(
                context = this,
                screen = screen,
                metadataReader = metadataReader,
                scope = lifecycleScope,
                host =
                    object : ViewerDetailsSheetController.Host {
                        override val request: PhotoRequest get() = this@PhotoViewerActivity.request
                        override val resolvedUri: Uri? get() = this@PhotoViewerActivity.resolvedUri
                        override val gesturesBlocked: Boolean get() = photoTransitioning || dismissing

                        override fun fittedMediaBottom(): Float? = this@PhotoViewerActivity.fittedMediaBottom()

                        override fun handlePhotoDismissDrag(
                            distance: Float,
                            velocity: Float,
                            finished: Boolean,
                        ) = dismiss.handlePhotoDismissDrag(distance, velocity, finished)

                        override fun resetPhotoDismiss() = dismiss.resetPhotoDismiss()
                    },
            )
        swipe =
            ViewerSwipeController(
                screen = screen,
                mediaTransform = mediaTransform,
                scope = lifecycleScope,
                loadThumbnail = { photo -> thumbnailSource.loadThumbnail(UserId(photo.userId), photo.nodeUid) },
                host =
                    object : ViewerSwipeController.Host {
                        override val request: PhotoRequest get() = this@PhotoViewerActivity.request
                        override val navigationRequests: List<PhotoRequest>
                            get() = this@PhotoViewerActivity.navigationRequests
                        override val gesturesBlocked: Boolean
                            get() = details.shown || photoTransitioning || dismissing

                        override fun activeMediaView(): View = this@PhotoViewerActivity.activeMediaView()

                        override fun beginNavigation() = this@PhotoViewerActivity.beginNavigation()

                        override fun commitNavigation(adjacent: PhotoRequest) =
                            this@PhotoViewerActivity.commitNavigation(adjacent)

                        override fun adoptPreview(bitmap: Bitmap) = this@PhotoViewerActivity.adoptPreview(bitmap)
                    },
            )
    }

    /** The single place the current request changes, so the intent never lags it. */
    private fun setCurrentRequest(value: PhotoRequest) {
        request = value
        request.writeTo(intent)
    }

    private fun updatePhotoDetailsLayout(availableHeight: Int) {
        if (availableHeight <= 0) return
        val mediaParams = mediaFrame.layoutParams as LinearLayout.LayoutParams
        val mediaHeight =
            PhotoViewerMediaLayoutPolicy.mediaHeight(
                viewportHeight = availableHeight,
                mediaTop = mediaFrame.top,
            )
        if (mediaHeight <= 0) return
        if (mediaParams.height != mediaHeight) {
            mediaParams.height = mediaHeight
            mediaFrame.layoutParams = mediaParams
        }
        detailsSheet.minimumHeight = (availableHeight * DETAILS_MINIMUM_HEIGHT_FRACTION).roundToInt()
        mediaFrame.post(::updateMediaBounds)
        photoDetailsScroll.post(details::synchronizeWithImage)
    }

    private fun loadPhoto() {
        photoLoadJob?.cancel()
        photoReady = false
        updateMediaTitle()
        retryButton.visibility = View.GONE
        photoView.contentDescription =
            getString(
                if (request.mediaKind == MediaKind.VIDEO) {
                    R.string.video_description
                } else {
                    R.string.photo_image_description
                },
                request.displayName.ifBlank { getString(R.string.photo) },
            )
        playerView.contentDescription = photoView.contentDescription
        // Favourite and delete act on the photo, not on the bytes, so they are usable at once.
        setActionsEnabled(true)
        val requestedPhoto = request
        if (requestedPhoto.displayName.isBlank()) resolveDisplayName(requestedPhoto)
        photoLoadJob =
            lifecycleScope.launch {
                showCachedProtonThumbnail(requestedPhoto)
                // Photos load the original quietly behind the preview; the spinner only appears
                // when there is nothing at all to show. Videos keep their download progress.
                if (requestedPhoto.mediaKind == MediaKind.VIDEO || !thumbnailPreview.isVisible) scheduleLoadingPanel()
                try {
                    val userId = UserId(requestedPhoto.userId)
                    val nodeUid = requestedPhoto.nodeUid
                    // A cached original goes straight up; the preview is only worth showing while
                    // a download is in flight, otherwise it would flash before the full picture.
                    val cachedOriginal =
                        if (requestedPhoto.mediaKind == MediaKind.VIDEO) {
                            null
                        } else {
                            withContext(Dispatchers.IO) { originalMedia.prepareCachedOriginal(userId, nodeUid) }
                        }
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    if (cachedOriginal != null) {
                        showMedia(Uri.fromFile(cachedOriginal))
                        return@launch
                    }
                    if (requestedPhoto.mediaKind != MediaKind.VIDEO) launch { showCachedProtonPreview(requestedPhoto) }
                    val file =
                        withContext(Dispatchers.IO) {
                            if (requestedPhoto.mediaKind == MediaKind.VIDEO) {
                                originalMedia.downloadOriginalProgressively(userId, nodeUid) { stream ->
                                    withContext(Dispatchers.Main.immediate) {
                                        if (request.stableId != requestedPhoto.stableId) {
                                            throw CancellationException("Viewer moved to different media")
                                        }
                                        showProgressiveVideo(stream, requestedPhoto.stableId)
                                    }
                                }
                            } else {
                                originalMedia.downloadOriginal(userId, nodeUid)
                            }
                        }
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    if (requestedPhoto.mediaKind != MediaKind.VIDEO) showMedia(Uri.fromFile(file))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    if (!retryButton.isVisible) {
                        handlePhotoLoadFailure(error, getString(R.string.could_not_download_proton_photo))
                    }
                }
            }
    }

    private fun showMedia(uri: Uri) {
        if (request.mediaKind == MediaKind.VIDEO) showVideo(uri) else showPhoto(uri)
    }

    /**
     * Timeline entries carry no file name, and asking Proton for it is a network round trip.
     * The name only feeds accessibility text and the details sheet, so it is resolved off the
     * critical path and applied once it arrives.
     */
    private fun resolveDisplayName(requestedPhoto: PhotoRequest) {
        lifecycleScope.launch {
            val name =
                withContext(Dispatchers.IO) {
                    originalMedia.getOriginalFileName(UserId(requestedPhoto.userId), requestedPhoto.nodeUid)
                }.orEmpty()
            if (name.isBlank()) return@launch
            navigationRequests =
                navigationRequests.map { item ->
                    if (item.stableId == requestedPhoto.stableId) item.copy(displayName = name) else item
                }
            if (request.stableId != requestedPhoto.stableId) return@launch
            setCurrentRequest(request.copy(displayName = name))
            photoView.contentDescription =
                getString(
                    if (request.mediaKind == MediaKind.VIDEO) {
                        R.string.video_description
                    } else {
                        R.string.photo_image_description
                    },
                    name,
                )
            playerView.contentDescription = photoView.contentDescription
        }
    }

    private suspend fun showCachedProtonThumbnail(requestedPhoto: PhotoRequest) {
        if (previewStableId == requestedPhoto.stableId && thumbnailPreview.isVisible) {
            // The peek that slid in during the swipe already shows this photo's thumbnail.
            photoView.alpha = 0f
            return
        }
        val bitmap =
            thumbnailSource.loadThumbnail(
                UserId(requestedPhoto.userId),
                requestedPhoto.nodeUid,
            )
        if (!PhotoPreviewPolicy.canShow(
                requestedPhoto.stableId,
                request.stableId,
                bitmap != null,
            )
        ) {
            return
        }

        clearThumbnailPreview()
        // A preview is a picture to look at, so any spinner scheduled for an empty screen goes.
        hideLoadingPanel()
        previewStableId = requestedPhoto.stableId
        thumbnailPreview.setImageBitmap(bitmap)
        thumbnailPreview.visibility = View.VISIBLE
        thumbnailPreview.animate().cancel()
        photoView.alpha = 0f
        thumbnailPreview.translationY = photoView.translationY
        thumbnailPreview.scaleX = photoView.scaleX
        thumbnailPreview.scaleY = photoView.scaleY
        thumbnailPreview.translationX = photoView.translationX
        // Shown at full opacity straight away: fading it up from black reads as a brightness dip.
        thumbnailPreview.alpha = 1f
        photoDetailsScroll.post(details::synchronizeWithImage)
    }

    /**
     * Sharpens the stand-in with the stored screen-sized preview while the original downloads.
     * The bitmap replaces the thumbnail in the same view with no animation, so the position,
     * scale and opacity of a running swipe or dismiss gesture are preserved. Once the original
     * has arrived there is nothing left to sharpen and the preview is dropped.
     */
    private suspend fun showCachedProtonPreview(requestedPhoto: PhotoRequest) {
        val metrics = resources.displayMetrics
        val bitmap =
            originalMedia.loadPreview(
                UserId(requestedPhoto.userId),
                requestedPhoto.nodeUid,
                targetLongEdge = max(metrics.widthPixels, metrics.heightPixels),
            ) ?: return
        if (request.stableId != requestedPhoto.stableId || photoReady) return
        // The preview goes into the photo view itself so zoom and pan work right away; the
        // original later takes over the same geometry. Any spinner scheduled for an empty
        // screen goes, and the thumbnail underneath is no longer needed.
        hideLoadingPanel()
        photoView.animate().cancel()
        photoView.visibility = View.VISIBLE
        photoView.showPlaceholder(bitmap)
        if (thumbnailPreview.isVisible) photoView.translationX = thumbnailPreview.translationX
        photoView.alpha = 1f
        clearThumbnailPreview()
        photoDetailsScroll.post(details::synchronizeWithImage)
    }

    private fun showPhoto(uri: Uri) {
        val requestedStableId = request.stableId
        resolvedUri = uri
        releasePlayer()
        playerView.visibility = View.GONE
        photoView.visibility = View.VISIBLE
        if (details.shown) details.ensureMetadataLoaded()
        photoView.load(uri) { result ->
            if (request.stableId != requestedStableId) return@load
            result
                .onSuccess {
                    navigationFallback = null
                    photoReady = true
                    hideLoadingPanel()
                    setActionsEnabled(true)
                    if (details.shown) details.ensureMetadataLoaded()
                    photoDetailsScroll.post {
                        if (request.stableId == requestedStableId) details.synchronizeWithImage()
                    }
                    if (thumbnailPreview.isVisible) {
                        // The full image fades in over the opaque thumbnail, which is only removed
                        // once the fade completes, so brightness never dips through the black scrim.
                        photoView.animate().cancel()
                        photoView.translationX = thumbnailPreview.translationX
                        photoView.alpha = 0f
                        photoView
                            .animate()
                            .alpha(1f)
                            .setDuration(FULL_QUALITY_CROSSFADE_MILLIS)
                            .withEndAction {
                                // Only clear the preview this fade covered; a swipe may already have
                                // installed the next photo's thumbnail while the fade was running.
                                if (request.stableId == requestedStableId) clearThumbnailPreview()
                            }.start()
                    } else {
                        photoView.alpha = 1f
                    }
                    prefetchAdjacentOriginals(requestedStableId)
                }.onFailure { error ->
                    handlePhotoLoadFailure(error, getString(R.string.could_not_display_photo))
                }
        }
    }

    private fun showVideo(uri: Uri) = showVideo(uri, dataSourceFactory = null)

    private fun showProgressiveVideo(
        stream: ProtonOriginalStream,
        requestedStableId: String,
    ) {
        showVideo(Uri.fromFile(stream.file), ProtonProgressiveDataSource.Factory(stream))
        cancelLoadingPanelDelay()
        loadingPanel.visibility = View.VISIBLE
        videoProgressJob =
            lifecycleScope.launch {
                stream.progress.collect { downloadProgress ->
                    if (request.stableId == requestedStableId && !photoReady) {
                        updateVideoDownloadProgress(downloadProgress)
                    }
                }
            }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun showVideo(
        uri: Uri,
        dataSourceFactory: ProtonProgressiveDataSource.Factory?,
    ) {
        val requestedStableId = request.stableId
        resolvedUri = uri
        if (details.shown && dataSourceFactory == null) details.ensureMetadataLoaded()
        photoView.clear()
        photoView.visibility = View.GONE
        releasePlayer()
        playerView.visibility = View.VISIBLE
        playerView.alpha = 0f
        val createdPlayer = ExoPlayer.Builder(this).build()
        player = createdPlayer
        playerView.player = createdPlayer
        createdPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_READY || request.stableId != requestedStableId) return
                    if (photoReady) return
                    navigationFallback = null
                    photoReady = true
                    videoProgressJob?.cancel()
                    videoProgressJob = null
                    hideLoadingPanel()
                    setActionsEnabled(true)
                    if (details.shown) details.ensureMetadataLoaded()
                    playerView.animate().cancel()
                    playerView.alpha = 0f
                    playerView
                        .animate()
                        .alpha(1f)
                        .setDuration(FULL_QUALITY_CROSSFADE_MILLIS)
                        .start()
                    if (thumbnailPreview.isVisible) {
                        thumbnailPreview.animate().cancel()
                        cancelPendingPreviewClear()
                        val clear =
                            Runnable {
                                pendingPreviewClear = null
                                if (request.stableId == requestedStableId) clearThumbnailPreview()
                            }
                        pendingPreviewClear = clear
                        thumbnailPreview.postDelayed(clear, FULL_QUALITY_CROSSFADE_MILLIS)
                    }
                    prefetchAdjacentOriginals(requestedStableId)
                }

                override fun onPlayerError(error: PlaybackException) {
                    LenswaveDiagnostics.reportFailure(LenswaveOperation.VIDEO_PLAYBACK, error)
                    if (request.stableId == requestedStableId) {
                        handlePhotoLoadFailure(error, getString(R.string.could_not_play_video))
                    }
                }
            },
        )
        val mediaItem = MediaItem.fromUri(uri)
        if (dataSourceFactory == null) {
            createdPlayer.setMediaItem(mediaItem)
        } else {
            createdPlayer.setMediaSource(
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem),
            )
        }
        createdPlayer.prepare()
        createdPlayer.playWhenReady = true
    }

    private fun releasePlayer() {
        videoProgressJob?.cancel()
        videoProgressJob = null
        playerView.player = null
        player?.release()
        player = null
    }

    private fun updateMediaTitle() {
        val title =
            PhotoViewerTitleFormatter.format(
                capturedAtEpochMillis = request.capturedAt,
                zoneId = ZoneId.systemDefault(),
                locale = Locale.getDefault(),
                use24HourTime = DateFormat.is24HourFormat(this),
            )
        mediaTitle.text = title.orEmpty()
        mediaTitle.visibility = if (title == null) View.GONE else View.VISIBLE
    }

    private fun updateVideoDownloadProgress(downloadProgress: ProtonOriginalDownloadProgress) {
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        if (downloadProgress.complete) {
            progress.isIndeterminate = false
            progress.max = VIDEO_PROGRESS_MAX
            progress.progress = VIDEO_PROGRESS_MAX
            status.setText(R.string.preparing_video)
            return
        }

        val totalBytes = downloadProgress.totalBytes
        val percent = downloadProgress.percent
        progress.isIndeterminate = percent == null
        if (percent == null || totalBytes == null) {
            status.text =
                getString(
                    R.string.downloading_video_size,
                    Formatter.formatShortFileSize(this, downloadProgress.downloadedBytes),
                )
            return
        }
        progress.max = VIDEO_PROGRESS_MAX
        progress.progress = percent * VIDEO_PROGRESS_MAX / 100
        status.text =
            getString(
                R.string.downloading_video_progress,
                Formatter.formatShortFileSize(this, downloadProgress.downloadedBytes),
                Formatter.formatShortFileSize(this, totalBytes),
                percent,
            )
    }

    private fun scheduleLoadingPanel() {
        cancelLoadingPanelDelay()
        status.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        loadingPanel.visibility = View.GONE
        val requestedStableId = request.stableId
        val runnable =
            Runnable {
                loadingPanelRunnable = null
                if (!photoReady && request.stableId == requestedStableId && !dismissing) {
                    loadingPanel.visibility = View.VISIBLE
                }
            }
        loadingPanelRunnable = runnable
        loadingPanel.postDelayed(runnable, LOADING_PANEL_DELAY_MILLIS)
    }

    private fun hideLoadingPanel() {
        cancelLoadingPanelDelay()
        loadingPanel.visibility = View.GONE
        loadingPanel.alpha = 1f
    }

    private fun cancelLoadingPanelDelay() {
        loadingPanelRunnable?.let(loadingPanel::removeCallbacks)
        loadingPanelRunnable = null
    }

    /** Called by the swipe controller before the current media slides away. */
    private fun beginNavigation() {
        val previousRequest = request
        photoTransitioning = true
        photoLoadJob?.cancel()
        cancelLoadingPanelDelay()
        navigationFallback = NavigationFallback(previousRequest, resolvedUri)
        setActionsEnabled(false)
    }

    /** Called by the swipe controller once the current media has slid away. */
    private fun commitNavigation(adjacent: PhotoRequest) {
        setCurrentRequest(adjacent)
        resetPhotoStateForNavigation()
        swipe.adoptPeekAsPreview()
        photoTransitioning = false
        loadPhoto()
    }

    /** Installs the peek's bitmap as this request's thumbnail stand-in after a completed swipe. */
    private fun adoptPreview(bitmap: Bitmap) {
        previewStableId = request.stableId
        thumbnailPreview.setImageBitmap(bitmap)
        thumbnailPreview.alpha = 1f
        thumbnailPreview.translationX = 0f
        thumbnailPreview.visibility = View.VISIBLE
        photoView.alpha = 0f
        photoDetailsScroll.post(details::synchronizeWithImage)
    }

    private fun resetPhotoStateForNavigation() {
        releasePlayer()
        playerView.visibility = View.GONE
        clearThumbnailPreview()
        photoView.clear()
        resolvedUri = null
        photoReady = false
        details.resetForNavigation()
        loadingPanel.visibility = View.GONE
        loadingPanel.translationX = 0f
        loadingPanel.translationY = 0f
        loadingPanel.alpha = 1f
        progress.visibility = View.VISIBLE
        status.visibility = View.GONE
        photoView.translationX = 0f
        photoView.translationY = 0f
        photoView.scaleX = 1f
        photoView.scaleY = 1f
        photoView.alpha = 1f
        playerView.translationX = 0f
        playerView.translationY = 0f
        playerView.scaleX = 1f
        playerView.scaleY = 1f
        playerView.alpha = 1f
        mediaTitle.translationX = 0f
        mediaTitle.translationY = 0f
        mediaTitle.alpha = 1f
        updateFavoriteButton(enabled = false)
    }

    private fun handlePhotoLoadFailure(
        error: Throwable,
        fallbackMessage: String,
    ) {
        val fallback = navigationFallback
        navigationFallback = null
        if (fallback != null) {
            clearThumbnailPreview()
            releasePlayer()
            setCurrentRequest(fallback.request)
            updateMediaTitle()
            resolvedUri = null
            photoTransitioning = false
            photoView.translationX = 0f
            photoView.alpha = 1f
            playerView.translationX = 0f
            playerView.alpha = 1f
            Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show()
            if (fallback.uri != null) {
                scheduleLoadingPanel()
                showMedia(fallback.uri)
            } else {
                loadPhoto()
            }
            return
        }
        photoTransitioning = false
        releasePlayer()
        photoView.translationX = 0f
        photoView.alpha = 1f
        playerView.translationX = 0f
        playerView.alpha = 1f
        cancelLoadingPanelDelay()
        loadingPanel.translationX = 0f
        loadingPanel.visibility = View.VISIBLE
        progress.visibility = View.GONE
        status.text = fallbackMessage
        status.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
    }

    private var actionsEnabled = false

    private fun setActionsEnabled(enabled: Boolean) {
        actionsEnabled = enabled
        deleteButton.isEnabled = enabled
        deleteButton.alpha = if (enabled) 1f else 0.45f
        updateFavoriteButton(enabled)
    }

    private fun updateFavoriteButton(enabled: Boolean = actionsEnabled) {
        favoriteButton.visibility = View.VISIBLE
        favoriteButton.isEnabled = enabled && !favoriteInProgress
        favoriteButton.alpha = if (favoriteButton.isEnabled) 1f else 0.45f
        UiStyle.applyFavoriteIcon(favoriteButton, request.isFavorite)
    }

    private fun toggleFavorite() {
        if (favoriteInProgress) return
        val userId = UserId(request.userId)
        val nodeUids = listOf(request.nodeUid)
        val favorite = !request.isFavorite
        favoriteInProgress = true
        updateFavoriteButton()
        lifecycleScope.launch {
            try {
                val result = photoMutations.setFavorite(userId, nodeUids, favorite)
                if (result.updatedCount != 1) {
                    Toast
                        .makeText(
                            this@PhotoViewerActivity,
                            R.string.could_not_update_favorite,
                            Toast.LENGTH_LONG,
                        ).show()
                    return@launch
                }
                setCurrentRequest(request.withFavorite(favorite))
                navigationRequests =
                    navigationRequests.map { item ->
                        if (item.stableId == request.stableId) item.withFavorite(favorite) else item
                    }
                setResult(Activity.RESULT_OK, resultIntent.putExtra(EXTRA_FAVORITE_CHANGED, true))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Toast
                    .makeText(
                        this@PhotoViewerActivity,
                        R.string.could_not_update_favorite,
                        Toast.LENGTH_LONG,
                    ).show()
            } finally {
                favoriteInProgress = false
                updateFavoriteButton()
            }
        }
    }

    /**
     * Decrypts the next photo's cached original ahead of a swipe so it opens from a ready file.
     * Only the direction of travel is prepared; on first open both neighbours are. Nothing is
     * downloaded: only originals already in the encrypted cache qualify.
     */
    private fun prefetchAdjacentOriginals(stableId: String) {
        val offsets = if (swipe.lastNavigationOffset == 0) listOf(-1, 1) else listOf(swipe.lastNavigationOffset)
        val neighbours =
            offsets
                .mapNotNull { offset -> swipe.adjacentTo(stableId, offset) }
                .filter { it.mediaKind != MediaKind.VIDEO }
        if (neighbours.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            neighbours.forEach { neighbour ->
                runCatching {
                    originalMedia.prepareCachedOriginal(UserId(neighbour.userId), neighbour.nodeUid)
                }
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                swipe.navigatePhoto(-1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                swipe.navigatePhoto(1)
                true
            }

            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                photoView.zoomIn()
                true
            }

            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                photoView.zoomOut()
                true
            }

            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                photoView.resetZoom()
                true
            }

            else -> {
                super.onKeyDown(keyCode, event)
            }
        }

    private fun activeMediaView(): View =
        if (request.mediaKind == MediaKind.VIDEO && playerView.isVisible) playerView else photoView

    private fun fittedMediaBottom(): Float? =
        if (request.mediaKind == MediaKind.VIDEO) {
            playerView.bottom.takeIf { it > playerView.top }?.toFloat()
        } else {
            // Before the full image arrives the thumbnail stands in, so the sheet attaches to it
            // instead of the bottom of the media frame and does not jump once the photo loads.
            photoView.fittedImageBottom()?.plus(photoView.top)
                ?: thumbnailPreview.takeIf { it.isVisible }?.fittedDrawableBottom()
        }

    private fun ImageView.fittedDrawableBottom(): Float? {
        val drawable = drawable ?: return null
        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val fittedScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        return top + (height + imageHeight * fittedScale) / 2f
    }

    private fun handleBack() {
        if (details.shown) details.hideDetails() else finish()
    }

    private fun deletePhoto() {
        if (PhotoDeletionPolicy.decide(listOf(request.toPhotoTarget())) !is PhotoDeletionDecision.Allowed) return
        confirmTrashProtonPhoto()
    }

    private fun confirmTrashProtonPhoto() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.move_to_proton_trash_question)
            .setMessage(R.string.recover_from_proton_trash)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.move_to_trash) { _, _ -> trashProtonPhoto() }
            .show()
    }

    private fun trashProtonPhoto() {
        if (deletionInProgress) return
        val userId = UserId(request.userId)
        val nodeUid = request.nodeUid
        deletionInProgress = true
        setActionsEnabled(false)
        lifecycleScope.launch {
            try {
                val result = photoDeletionExecutor.trashProton(userId, listOf(nodeUid))
                if (result.successfulCount == 1) {
                    finishDeleted()
                } else {
                    deletionInProgress = false
                    setActionsEnabled(true)
                    Toast
                        .makeText(
                            this@PhotoViewerActivity,
                            R.string.could_not_move_to_proton_trash,
                            Toast.LENGTH_LONG,
                        ).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                deletionInProgress = false
                setActionsEnabled(true)
                Toast
                    .makeText(
                        this@PhotoViewerActivity,
                        R.string.could_not_move_to_proton_trash,
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    private fun finishDeleted() {
        setResult(Activity.RESULT_OK, resultIntent.putExtra(EXTRA_PHOTO_DELETED, true))
        finish()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeArea: Insets =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            actions.applyBottomOverlayInsets(safeArea)
            mediaTitle.setPadding(
                dp(16) + safeArea.left,
                dp(10) + safeArea.top,
                dp(16) + safeArea.right,
                dp(10),
            )
            actions.post(::updateMediaBounds)
            detailsSheet.setPadding(
                dp(16) + safeArea.left,
                dp(8),
                dp(16) + safeArea.right,
                dp(10) + safeArea.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateMediaBounds() {
        val inset =
            PhotoViewerMediaLayoutPolicy.verticalInset(
                viewportHeight = mediaFrame.height,
                titleBottom = if (mediaTitle.isVisible) mediaTitle.bottom else 0,
                actionsTop = actions.top + photoDetailsScroll.scrollY - mediaFrame.top,
                gap = dp(MEDIA_ACTION_GAP_DP),
            )
        if (inset <= 0) return
        listOf(photoView, thumbnailPreview, peekPreview, playerView, loadingPanel).forEach { media ->
            (media.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = inset
                bottomMargin = inset
                media.layoutParams = this
            }
        }
        if (photoReady || thumbnailPreview.isVisible) photoDetailsScroll.post(details::synchronizeWithImage)
    }

    private fun cancelPendingPreviewClear() {
        pendingPreviewClear?.let(thumbnailPreview::removeCallbacks)
        pendingPreviewClear = null
    }

    private fun clearThumbnailPreview() {
        cancelPendingPreviewClear()
        thumbnailPreview.animate().cancel()
        thumbnailPreview.setImageDrawable(null)
        thumbnailPreview.visibility = View.GONE
        thumbnailPreview.alpha = 0f
        thumbnailPreview.translationX = 0f
        thumbnailPreview.translationY = 0f
        thumbnailPreview.scaleX = 1f
        thumbnailPreview.scaleY = 1f
        previewStableId = null
    }

    private data class NavigationFallback(
        val request: PhotoRequest,
        val uri: Uri?,
    )

    companion object {
        const val EXTRA_PROTON_NODE_UID = "com.bownee.lenswave.extra.PROTON_NODE_UID"
        const val EXTRA_USER_ID = "com.bownee.lenswave.extra.USER_ID"
        const val EXTRA_CAPTURED_AT = "com.bownee.lenswave.extra.CAPTURED_AT"
        const val EXTRA_DISPLAY_NAME = "com.bownee.lenswave.extra.DISPLAY_NAME"
        const val EXTRA_STABLE_ID = "com.bownee.lenswave.extra.STABLE_ID"
        const val EXTRA_MEDIA_KIND = "com.bownee.lenswave.extra.MEDIA_KIND"
        const val EXTRA_IS_FAVORITE = "com.bownee.lenswave.extra.IS_FAVORITE"
        const val EXTRA_PHOTO_DELETED = "com.bownee.lenswave.extra.PHOTO_DELETED"
        const val EXTRA_FAVORITE_CHANGED = "com.bownee.lenswave.extra.FAVORITE_CHANGED"
        const val EXTRA_NAVIGATION = "com.bownee.lenswave.extra.NAVIGATION"
        private const val DETAILS_MINIMUM_HEIGHT_FRACTION = 0.55f
        private const val MEDIA_ACTION_GAP_DP = 8
        private const val VIDEO_CONTROLS_HEIGHT_DP = 80
        private const val FULL_QUALITY_CROSSFADE_MILLIS = 180L
        private const val LOADING_PANEL_DELAY_MILLIS = 300L
        private const val VIDEO_PROGRESS_MAX = 1_000

        fun createIntent(
            context: Context,
            photo: GalleryAsset,
            userId: UserId,
            navigation: List<GalleryAsset> = listOf(photo),
        ): Intent {
            val request = PhotoRequest.from(photo, userId.id)
            val currentIndex = navigation.indexOfFirst { it.stableId == photo.stableId }.coerceAtLeast(0)
            val from = (currentIndex - NAVIGATION_RADIUS).coerceAtLeast(0)
            val to = (currentIndex + NAVIGATION_RADIUS + 1).coerceAtMost(navigation.size)
            return request.writeTo(Intent(context, PhotoViewerActivity::class.java)).apply {
                putParcelableArrayListExtra(
                    EXTRA_NAVIGATION,
                    ArrayList(
                        navigation.subList(from, to).map {
                            PhotoRequest.from(it, userId.id).toBundle()
                        },
                    ),
                )
            }
        }

        private const val NAVIGATION_RADIUS = 100
    }
}
