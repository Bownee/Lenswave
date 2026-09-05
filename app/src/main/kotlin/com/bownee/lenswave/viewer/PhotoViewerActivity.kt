package com.bownee.lenswave.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.R
import com.bownee.lenswave.UiStyle
import com.bownee.lenswave.applyBottomOverlayInsets
import com.bownee.lenswave.configureEdgeToEdgeWindow
import com.bownee.lenswave.dp
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.GalleryNavigationCodec
import com.bownee.lenswave.gallery.MediaKind
import com.bownee.lenswave.gallery.PhotoDeletionDecision
import com.bownee.lenswave.gallery.PhotoDeletionPolicy
import com.bownee.lenswave.gallery.ProtonOriginalMediaSource
import com.bownee.lenswave.gallery.ProtonThumbnailImageSource
import com.bownee.lenswave.gallery.StoredGalleryNavigation
import com.bownee.lenswave.gallery.TrashConfirmationDialogFragment
import com.bownee.lenswave.metadata.PhotoMetadataHints
import com.bownee.lenswave.metadata.PhotoMetadataReader
import com.bownee.lenswave.proton.ProtonPreviewOrientation
import com.bownee.lenswave.proton.ProtonSessionChangedException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
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
 * [ViewerDismissController] and [ViewerDetailsSheetController], and video playback to
 * [ViewerVideoController].
 */
@AndroidEntryPoint
class PhotoViewerActivity :
    FragmentActivity(),
    TrashConfirmationDialogFragment.Listener {
    @Inject lateinit var originalMedia: ProtonOriginalMediaSource

    @Inject lateinit var thumbnailSource: ProtonThumbnailImageSource

    @Inject lateinit var metadataReader: PhotoMetadataReader

    @Inject lateinit var mutationCoordinator: ViewerMutationCoordinator

    @Inject lateinit var navigationSourceProvider: PhotoNavigationSourceProvider

    @Inject lateinit var privacySettings: ViewerPrivacySettings

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
    private lateinit var video: ViewerVideoController
    private lateinit var request: PhotoRequest
    private var navigationRequests: MutableList<PhotoRequest> = mutableListOf()

    /** Index of the first entry of [navigationRequests] in the gallery's list; -1 when unknown. */
    private var navigationStart = -1

    /** Size of the gallery list the window was cut from; -1 when unknown. */
    private var navigationTotal = -1

    /** The gallery page the list came from, for rebuilding it after a process death. */
    private var sourceDestination: GalleryDestination? = null
    private var navigationRehydration: Job? = null
    private var navigationRehydrationFailed = false
    private var edgeToast: Toast? = null

    /** A zoom kept across recreation, applied once the restored photo's original is decoded. */
    private var pendingZoomFactor: Float? = null

    /** The gallery's full list, when it is still in the process and lines up with [navigationRequests]. */
    private var navigationSource: PhotoNavigationSources.Source? = null

    /** The token the intent carried, cleared when the viewer finishes whether or not its list was adopted. */
    private val intentNavigationToken: Long
        get() = intent.getLongExtra(EXTRA_NAVIGATION_TOKEN, PhotoNavigationSources.NO_TOKEN)
    private val navigationWindow: PhotoNavigationWindowPolicy.Window?
        get() =
            navigationStart.takeIf { it >= 0 }?.let {
                PhotoNavigationWindowPolicy.Window(
                    it,
                    it + navigationRequests.size,
                )
            }
    private var resolvedUri: Uri? = null
    private var photoTransitioning = false
    private var dismissing = false
    private var photoReady = false
    private var previewStableId: String? = null
    private var navigationFallback: NavigationFallback? = null
    private var photoLoadJob: Job? = null

    /**
     * The neighbour's cached original being decrypted ahead of a swipe, and which photo it is
     * for. A swipe onto that very photo keeps the job and awaits it instead of cancelling it,
     * which would delete the partial plaintext and start the decrypt over.
     */
    private var prefetchJob: Deferred<Result<File?>>? = null
    private var prefetchStableId: String? = null

    /**
     * The screen-sized preview being decrypted and decoded while the original downloads. It
     * outlives the load's body as a child coroutine, so it is cancelled by hand once the original
     * wins: a preview finishing afterwards was dropped anyway, having evicted a neighbour's from
     * the preview cache for nothing.
     */
    private var previewJob: Job? = null
    private var loadingPanelRunnable: Runnable? = null

    /** Set in onDestroy; posted work that outlives the Activity checks it and does nothing. */
    private var destroyed = false

    /**
     * Accumulates what the gallery must refresh; a later result must not drop an earlier flag,
     * and a recreation must not drop any of them either (see [onSaveInstanceState]).
     */
    private val resultIntent = Intent()

    /** A mutation of the photo on screen is still running in [mutationCoordinator]. */
    private val mutationInFlight: Boolean get() = mutationCoordinator.isInFlight(request.stableId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set before any content: a secure window keeps the photos out of screenshots and recordings.
        applySecureWindowFlag()
        restoreNavigation(savedInstanceState)
        restoreNavigationSource()
        // A restored window may already have its edge near; the same check a swipe makes.
        extendNavigationWindow()
        restoreResult(savedInstanceState)
        configureEdgeToEdgeWindow()
        buildInterface()
        restoreViewingState(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )
        observeMutationOutcomes()
        loadPhoto()
    }

    /**
     * The photo on screen and the navigation window around it go to the saved state, so a
     * recreated viewer (a rotation, or a process death) reopens where the user was rather than
     * where the gallery opened it. The window is cut down to the policy's radius around the
     * current photo: a long swipe run can have grown it to hundreds of entries, and the state
     * bundle has a hard size limit.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(STATE_REQUEST, request)
        val local = navigationRequests.indexOfFirst { it.stableId == request.stableId }.coerceAtLeast(0)
        val kept = PhotoNavigationWindowPolicy.initial(local, navigationRequests.size)
        outState.putParcelableArrayList(STATE_NAVIGATION, ArrayList(navigationRequests.subList(kept.start, kept.end)))
        outState.putInt(STATE_NAVIGATION_START, if (navigationStart < 0) -1 else navigationStart + kept.start)
        outState.putInt(STATE_NAVIGATION_TOTAL, navigationTotal)
        RESULT_EXTRAS.forEach { extra -> outState.putBoolean(extra, resultIntent.getBooleanExtra(extra, false)) }
        // How the photo is being looked at: the open sheet, the zoom, and where a video is.
        outState.putBoolean(STATE_DETAILS_SHOWN, details.shown)
        outState.putFloat(STATE_ZOOM_FACTOR, photoView.zoomFactor())
        video.playbackState()?.let { playback ->
            outState.putLong(STATE_VIDEO_POSITION, playback.positionMillis)
            outState.putBoolean(STATE_VIDEO_PLAY_WHEN_READY, playback.playWhenReady)
        }
        super.onSaveInstanceState(outState)
    }

    /** Re-applies what [onSaveInstanceState] kept of how the photo was being looked at; call once the views exist. */
    private fun restoreViewingState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        if (savedInstanceState.getBoolean(STATE_DETAILS_SHOWN, false)) details.restoreShown()
        val zoom = savedInstanceState.getFloat(STATE_ZOOM_FACTOR, 1f)
        if (zoom > ZOOM_RESTORE_THRESHOLD) pendingZoomFactor = zoom
        if (savedInstanceState.containsKey(STATE_VIDEO_POSITION)) {
            video.restorePlayback(
                ViewerPlaybackState(
                    positionMillis = savedInstanceState.getLong(STATE_VIDEO_POSITION, 0L),
                    playWhenReady = savedInstanceState.getBoolean(STATE_VIDEO_PLAY_WHEN_READY, true),
                ),
            )
        }
    }

    /** The saved state wins over the intent: the intent describes where the viewer was opened, not where it is. */
    private fun restoreNavigation(savedInstanceState: Bundle?) {
        val savedRequest =
            savedInstanceState?.let {
                BundleCompat.getParcelable(
                    it,
                    STATE_REQUEST,
                    PhotoRequest::class.java,
                )
            }
        if (savedInstanceState != null && savedRequest != null) {
            request = savedRequest
            navigationRequests =
                BundleCompat
                    .getParcelableArrayList(savedInstanceState, STATE_NAVIGATION, PhotoRequest::class.java)
                    .orEmpty()
                    .ifEmpty { listOf(request) }
                    .toMutableList()
            navigationStart = savedInstanceState.getInt(STATE_NAVIGATION_START, -1)
            navigationTotal = savedInstanceState.getInt(STATE_NAVIGATION_TOTAL, -1)
        } else {
            request = PhotoRequest.from(intent)
            navigationRequests = PhotoRequest.navigationFrom(intent).ifEmpty { listOf(request) }.toMutableList()
            navigationStart = intent.getIntExtra(EXTRA_NAVIGATION_START, -1)
            navigationTotal = intent.getIntExtra(EXTRA_NAVIGATION_TOTAL, -1)
        }
        sourceDestination = sourceDestination(intent)
    }

    /** Whether the gallery list had entries beyond the window in the direction of [offset]. */
    private fun galleryContinues(offset: Int): Boolean {
        val window = navigationWindow ?: return false
        return if (offset < 0) window.start > 0 else navigationTotal > window.end
    }

    /**
     * After a process death the in-process list is gone. This rebuilds it from the page's cache
     * around the current photo, once, the first time the window would need to grow; while it is
     * loading (or if it cannot be rebuilt) a swipe past the edge says so instead of bouncing.
     */
    private fun rehydrateNavigationSource() {
        if (navigationRehydration != null || navigationRehydrationFailed) return
        val destination = sourceDestination ?: return
        val userId = UserId(request.userId)
        navigationRehydration =
            lifecycleScope.launch {
                val assets = withContext(Dispatchers.IO) { navigationSourceProvider.load(destination, userId) }
                if (assets == null || !adoptNavigationSource(assets, userId)) navigationRehydrationFailed = true
            }
    }

    /** Re-cuts the window from [assets] around the current photo, keeping entries already resolved. */
    private fun adoptNavigationSource(
        assets: List<GalleryAsset>,
        userId: UserId,
    ): Boolean {
        val index = assets.indexOfFirst { it.stableId == request.stableId }
        if (index < 0) return false
        val window = PhotoNavigationWindowPolicy.initial(index, assets.size)
        val existing = navigationRequests.associateBy { it.stableId }
        navigationRequests =
            assets.subList(window.start, window.end).mapTo(ArrayList(window.size)) { asset ->
                existing[asset.stableId] ?: PhotoRequest.from(asset, userId.id)
            }
        navigationStart = window.start
        navigationTotal = assets.size
        navigationSource = PhotoNavigationSources.Source(PhotoNavigationSources.NO_TOKEN, userId.id, assets)
        return true
    }

    private fun showNavigationEdge(offset: Int) {
        // With the list at hand the window grows before the edge, so an edge here is the gallery's own.
        if (navigationSource != null || !galleryContinues(offset)) return
        rehydrateNavigationSource()
        edgeToast?.cancel()
        edgeToast = Toast.makeText(this, R.string.no_more_photos_loaded, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun restoreResult(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        RESULT_EXTRAS.forEach { extra -> if (savedInstanceState.getBoolean(extra, false)) recordResult(extra) }
    }

    private fun recordResult(extra: String) {
        setResult(Activity.RESULT_OK, resultIntent.putExtra(extra, true))
    }

    /**
     * Applies the outcome of every favourite or trash call on a photo of this viewer, including
     * one that finished while recreating. Outcomes of photos outside its window are left in the
     * coordinator for the gallery (see [ViewerOutcomePolicy]); an outcome taken here is recorded
     * in the result before anything else, so the gallery's refresh cannot be lost to a finish.
     *
     * A finishing viewer takes nothing: it stays STARTED until it is gone, but a result set after
     * finish() never reaches the gallery, so an outcome consumed then would be lost. Left in the
     * queue, the gallery's own collector takes it once the viewer has closed.
     */
    private fun observeMutationOutcomes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mutationCoordinator.outcomes.collect { outcomes ->
                    for (outcome in outcomes) {
                        if (isFinishing) break
                        if (ViewerOutcomePolicy.consumes(outcome.stableId, request.stableId, navigationRequests)) {
                            applyMutationOutcome(outcome)
                        }
                    }
                }
            }
        }
    }

    private fun applyMutationOutcome(outcome: ViewerMutationCoordinator.Outcome) {
        mutationCoordinator.consume(outcome)
        when (outcome) {
            is ViewerMutationCoordinator.Outcome.FavoriteSet -> {
                if (outcome.succeeded) {
                    updateNavigationRequest(outcome.stableId) { item -> item.withFavorite(outcome.favorite) }
                    if (request.stableId == outcome.stableId) setCurrentRequest(request.withFavorite(outcome.favorite))
                    recordResult(EXTRA_FAVORITE_CHANGED)
                } else {
                    Toast.makeText(this, R.string.could_not_update_favorite, Toast.LENGTH_LONG).show()
                }
                updateFavoriteButton()
            }

            is ViewerMutationCoordinator.Outcome.Trashed -> {
                if (outcome.succeeded) {
                    recordResult(EXTRA_PHOTO_DELETED)
                    if (request.stableId == outcome.stableId) finish()
                } else {
                    Toast.makeText(this, R.string.could_not_move_to_proton_trash, Toast.LENGTH_LONG).show()
                    setActionsEnabled(actionsEnabled)
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        photoLoadJob?.cancel()
        cancelPreviewLoad()
        prefetchJob?.cancel()
        // An onCreate that failed before buildInterface has none of these; touching them would
        // raise UninitializedPropertyAccessException over the failure that actually happened.
        if (this::screen.isInitialized) {
            photoDetailsScroll.removeCallbacks(detailsSynchronizationRunnable)
            mediaFrame.removeCallbacks(mediaBoundsRunnable)
            cancelLoadingPanelDelay()
            photoView.close()
        }
        if (this::details.isInitialized) details.release()
        if (this::swipe.isInitialized) swipe.release()
        if (this::video.isInitialized) {
            // Clearing the thumbnail also withdraws the video controller's pending clear.
            clearThumbnailPreview()
            video.release()
        }
        if (isFinishing) {
            // The intent's list goes whether or not it was adopted: a window that no longer lined
            // up, or a source rebuilt after a process death, would otherwise leave the gallery's
            // full list retained until RETAINED_SOURCES pushes it out.
            PhotoNavigationSources.clear(intentNavigationToken)
            navigationSource?.let { PhotoNavigationSources.clear(it.token) }
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        video.resume()
    }

    override fun onResume() {
        super.onResume()
        // A toggle in the settings takes effect on the way back, without recreating the viewer.
        applySecureWindowFlag()
    }

    private fun applySecureWindowFlag() {
        if (privacySettings.blockScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        video.pause()
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
            if (top != oldTop || bottom != oldBottom) scheduleMediaBoundsUpdate()
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

                        override fun metadataHints(uri: Uri): PhotoMetadataHints? = photoView.metadataHints(uri)

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
                loadThumbnail = ::readThumbnail,
                peekThumbnail = { photo -> thumbnailSource.peekThumbnail(UserId(photo.userId), photo.nodeUid) },
                host =
                    object : ViewerSwipeController.Host {
                        override val request: PhotoRequest get() = this@PhotoViewerActivity.request
                        override val navigationRequests: List<PhotoRequest>
                            get() = this@PhotoViewerActivity.navigationRequests
                        override val gesturesBlocked: Boolean
                            get() = details.shown || photoTransitioning || dismissing

                        override fun activeMediaView(): View = this@PhotoViewerActivity.activeMediaView()

                        override fun beginNavigation(target: PhotoRequest) =
                            this@PhotoViewerActivity.beginNavigation(target)

                        override fun commitNavigation(adjacent: PhotoRequest) =
                            this@PhotoViewerActivity.commitNavigation(adjacent)

                        override fun adoptPreview(bitmap: Bitmap) = this@PhotoViewerActivity.adoptPreview(bitmap)

                        override fun onNavigationEdge(offset: Int) = showNavigationEdge(offset)
                    },
            )
        video =
            ViewerVideoController(
                context = this,
                screen = screen,
                scope = lifecycleScope,
                host =
                    object : ViewerVideoController.Host {
                        override val currentStableId: String get() = request.stableId
                        override val mediaReady: Boolean get() = photoReady
                        override val detailsShown: Boolean get() = details.shown

                        override fun onMediaResolved(uri: Uri) {
                            resolvedUri = uri
                        }

                        override fun ensureDetailsMetadataLoaded() = details.ensureMetadataLoaded()

                        override fun showLoadingPanelImmediately() {
                            cancelLoadingPanelDelay()
                            loadingPanel.visibility = View.VISIBLE
                        }

                        override fun hideLoadingPanel() = this@PhotoViewerActivity.hideLoadingPanel()

                        override fun onVideoReady(requestedStableId: String) {
                            navigationFallback = null
                            photoReady = true
                            hideLoadingPanel()
                            setActionsEnabled(true)
                            if (details.shown) details.ensureMetadataLoaded()
                            prefetchAdjacentOriginals(requestedStableId)
                        }

                        override fun clearThumbnailPreview() = this@PhotoViewerActivity.clearThumbnailPreview()

                        override fun reloadMedia() = loadPhoto()

                        override fun handleLoadFailure(
                            error: Throwable,
                            fallbackMessage: String,
                        ) = handlePhotoLoadFailure(error, fallbackMessage)
                    },
            )
    }

    /** The single place the current request changes; [onSaveInstanceState] carries it across recreation. */
    private fun setCurrentRequest(value: PhotoRequest) {
        request = value
    }

    /**
     * Finds the in-process gallery list the window was cut from. The window is trusted only if
     * it still lines up with that list; after a process death there is no list and the viewer
     * keeps the window it was restored with.
     */
    private fun restoreNavigationSource() {
        val source = PhotoNavigationSources.find(intentNavigationToken) ?: return
        val window = navigationWindow ?: return
        val linesUp =
            window.end <= source.assets.size &&
                source.assets[window.start].stableId == navigationRequests.first().stableId &&
                source.assets[window.end - 1].stableId == navigationRequests.last().stableId
        if (!linesUp) return
        navigationSource = source
    }

    /**
     * Grows the navigation window from the gallery list while the user swipes towards an edge.
     * Existing entries are kept (they may carry a resolved name or a changed favourite); the
     * list is replaced rather than mutated so the swipe controller rebuilds its index.
     */
    private fun extendNavigationWindow() {
        val window = navigationWindow ?: return
        val local = navigationRequests.indexOfFirst { it.stableId == request.stableId }
        if (local < 0) return
        val source = navigationSource
        if (source == null) {
            // Nothing to grow from in the process; rebuild the list once the edge comes near.
            if (PhotoNavigationWindowPolicy.extended(window, window.start + local, navigationTotal) != null) {
                rehydrateNavigationSource()
            }
            return
        }
        val grown = PhotoNavigationWindowPolicy.extended(window, window.start + local, source.assets.size) ?: return
        val extended = ArrayList<PhotoRequest>(grown.size)
        source.assets.subList(grown.start, window.start).mapTo(extended) { PhotoRequest.from(it, source.userId) }
        extended.addAll(navigationRequests)
        source.assets.subList(window.end, grown.end).mapTo(extended) { PhotoRequest.from(it, source.userId) }
        navigationRequests = extended
        navigationStart = grown.start
    }

    /**
     * Replaces one entry of the navigation list in place. The list keeps its identity and order,
     * so the swipe controller's index stays valid and nothing copies the whole window.
     */
    private fun updateNavigationRequest(
        stableId: String,
        transform: (PhotoRequest) -> PhotoRequest,
    ) {
        val index = navigationRequests.indexOfFirst { it.stableId == stableId }
        if (index >= 0) navigationRequests[index] = transform(navigationRequests[index])
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
        scheduleMediaBoundsUpdate()
        postDetailsSynchronization()
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
        // A thumbnail already decoded in memory goes up in this very frame, before any coroutine.
        val thumbnailShown = showCachedProtonThumbnail(requestedPhoto)
        photoLoadJob =
            lifecycleScope.launch {
                val userId = UserId(requestedPhoto.userId)
                val nodeUid = requestedPhoto.nodeUid
                // A cached original goes straight up; the preview is only worth showing while
                // a download is in flight, otherwise it would flash before the full picture.
                // Preparing it starts at once so it overlaps the thumbnail read rather than
                // waiting behind it. Failures are carried as a Result so they surface through the
                // catch below instead of failing this whole job through structured concurrency.
                // A prefetch already decrypting this very photo is awaited rather than raced: its
                // own probe would find the plaintext half-written and start a second decrypt.
                val prefetched =
                    prefetchJob?.takeIf { ViewerPrefetchPolicy.isFor(prefetchStableId, requestedPhoto.stableId) }
                val cachedOriginalPreparation =
                    if (requestedPhoto.mediaKind == MediaKind.VIDEO) {
                        null
                    } else {
                        async(Dispatchers.IO) {
                            // A prefetch cancelled or failed underneath only means "probe yourself";
                            // its cancellation is not this load's, which ensureActive re-checks.
                            val fromPrefetch = prefetched?.let { runCatching { it.await() }.getOrNull()?.getOrNull() }
                            ensureActive()
                            if (fromPrefetch != null) {
                                Result.success(fromPrefetch)
                            } else {
                                runCatching { originalMedia.prepareCachedOriginal(userId, nodeUid) }
                            }
                        }
                    }
                // A photo's thumbnail read races the cached-original probe instead of holding it
                // up: a prefetched neighbour, the common case after a swipe, hits the probe in a
                // few milliseconds and its thumbnail would only flash before the picture. The read
                // installs the thumbnail only while no cached original has been found and the
                // screen still wants a stand-in; it lives outside the try so a failed load can
                // cancel it before the retry panel it would otherwise cover goes up.
                var cachedOriginalFound = false
                var thumbnailLoad: Job? = null
                try {
                    thumbnailLoad =
                        when {
                            thumbnailShown -> {
                                null
                            }

                            cachedOriginalPreparation == null -> {
                                loadProtonThumbnail(requestedPhoto)
                                null
                            }

                            else -> {
                                launch {
                                    val bitmap = readThumbnail(requestedPhoto)
                                    if (cachedOriginalFound || bitmap == null) return@launch
                                    if (!PhotoPreviewPolicy.canShow(requestedPhoto.stableId, request.stableId, true)) {
                                        return@launch
                                    }
                                    if (!PhotoPreviewPolicy.wantsStandIn(
                                            photoReady,
                                            retryButton.isVisible,
                                        )
                                    ) {
                                        return@launch
                                    }
                                    installThumbnailPreview(requestedPhoto, bitmap)
                                }
                            }
                        }
                    // Photos load the original quietly behind the preview; the spinner only appears
                    // when there is nothing at all to show, and a thumbnail arriving within the
                    // delay withdraws it. Videos keep their download progress.
                    if (requestedPhoto.mediaKind == MediaKind.VIDEO || !thumbnailPreview.isVisible) {
                        scheduleLoadingPanel()
                    }
                    val cachedOriginal = cachedOriginalPreparation?.await()?.getOrThrow()
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    if (cachedOriginal != null) {
                        cachedOriginalFound = true
                        thumbnailLoad?.cancel()
                        showMedia(Uri.fromFile(cachedOriginal))
                        return@launch
                    }
                    if (requestedPhoto.mediaKind != MediaKind.VIDEO) {
                        previewJob = launch { showCachedProtonPreview(requestedPhoto) }
                    }
                    val file =
                        withContext(Dispatchers.IO) {
                            if (requestedPhoto.mediaKind == MediaKind.VIDEO) {
                                originalMedia.downloadOriginalProgressively(userId, nodeUid) { stream ->
                                    withContext(Dispatchers.Main.immediate) {
                                        if (request.stableId != requestedPhoto.stableId) {
                                            throw CancellationException("Viewer moved to different media")
                                        }
                                        video.showProgressive(stream, requestedPhoto.stableId)
                                    }
                                }
                            } else {
                                originalMedia.downloadOriginal(userId, nodeUid)
                            }
                        }
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    if (requestedPhoto.mediaKind != MediaKind.VIDEO) showMedia(Uri.fromFile(file))
                } catch (error: ProtonSessionChangedException) {
                    // A cancellation subtype, but not this job's: the account changed underneath
                    // the read (a viewer relaunched from recents after a sign-out, say). Left to
                    // the branch below it would end the job with the spinner still up and no retry.
                    thumbnailLoad?.cancel()
                    cancelPreviewLoad()
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    handlePhotoLoadFailure(error, getString(R.string.could_not_open_proton_photo_signed_out))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    thumbnailLoad?.cancel()
                    cancelPreviewLoad()
                    if (request.stableId != requestedPhoto.stableId) return@launch
                    if (!retryButton.isVisible) {
                        handlePhotoLoadFailure(error, getString(R.string.could_not_download_proton_photo))
                    }
                }
            }
    }

    private fun showMedia(uri: Uri) {
        if (request.mediaKind == MediaKind.VIDEO) video.show(uri) else showPhoto(uri)
    }

    /** Stops the preview read: the original has won, failed, or the viewer moved on. */
    private fun cancelPreviewLoad() {
        previewJob?.cancel()
        previewJob = null
    }

    /**
     * Timeline entries carry no file name, and asking Proton for it is a network round trip.
     * The name only feeds accessibility text and the details sheet, so it is resolved off the
     * critical path and applied once it arrives.
     */
    private fun resolveDisplayName(requestedPhoto: PhotoRequest) {
        lifecycleScope.launch {
            val name =
                optionalGatewayRead(LenswaveOperation.ORIGINAL_NAME_LOAD) {
                    originalMedia.getOriginalFileName(UserId(requestedPhoto.userId), requestedPhoto.nodeUid)
                }.orEmpty()
            if (name.isBlank()) return@launch
            updateNavigationRequest(requestedPhoto.stableId) { item -> item.copy(displayName = name) }
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

    /**
     * Runs a gateway read whose result the viewer can do without (a thumbnail, a preview, a file
     * name). The gateway signals an account change with [ProtonSessionChangedException], a
     * [CancellationException] subtype that is not this coroutine's own cancellation: swallowed
     * here it reads as "nothing to show", where it would otherwise end the coroutine silently.
     * Any other failure is a failure of the read, not of the viewer, and is reported the same way.
     */
    private suspend fun <T> optionalGatewayRead(
        operation: LenswaveOperation,
        read: suspend () -> T?,
    ): T? =
        try {
            read()
        } catch (_: ProtonSessionChangedException) {
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(operation, error)
            null
        }

    /**
     * Shows the thumbnail without leaving the main thread when it is already on screen from the
     * swipe's peek or decoded in the thumbnail cache. Returns false when it must be read from
     * disk through [loadProtonThumbnail].
     */
    private fun showCachedProtonThumbnail(requestedPhoto: PhotoRequest): Boolean {
        if (previewStableId == requestedPhoto.stableId && thumbnailPreview.isVisible) {
            // The peek that slid in during the swipe already shows this photo's thumbnail.
            photoView.alpha = 0f
            return true
        }
        val bitmap =
            thumbnailSource.peekThumbnail(UserId(requestedPhoto.userId), requestedPhoto.nodeUid) ?: return false
        installThumbnailPreview(requestedPhoto, bitmap)
        return true
    }

    /** The thumbnail from the store, or null when there is none or the session ended; never throws for the read. */
    private suspend fun readThumbnail(photo: PhotoRequest): Bitmap? =
        optionalGatewayRead(LenswaveOperation.RENDITION_READ) {
            thumbnailSource.loadThumbnail(UserId(photo.userId), photo.nodeUid)
        }

    private suspend fun loadProtonThumbnail(requestedPhoto: PhotoRequest) {
        val bitmap = readThumbnail(requestedPhoto)
        if (!PhotoPreviewPolicy.canShow(
                requestedPhoto.stableId,
                request.stableId,
                bitmap != null,
            )
        ) {
            return
        }
        installThumbnailPreview(requestedPhoto, requireNotNull(bitmap))
    }

    private fun installThumbnailPreview(
        requestedPhoto: PhotoRequest,
        bitmap: Bitmap,
    ) {
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
        postDetailsSynchronization()
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
            optionalGatewayRead(LenswaveOperation.RENDITION_READ) {
                originalMedia.loadPreview(
                    UserId(requestedPhoto.userId),
                    requestedPhoto.nodeUid,
                    targetLongEdge = max(metrics.widthPixels, metrics.heightPixels),
                )
            } ?: return
        if (request.stableId != requestedPhoto.stableId) return
        if (!PhotoPreviewPolicy.wantsStandIn(photoReady, retryButton.isVisible)) return
        // The preview goes into the photo view itself so zoom and pan work right away; the
        // original later takes over the same geometry. Any spinner scheduled for an empty
        // screen goes, and the thumbnail underneath is no longer needed.
        hideLoadingPanel()
        photoView.animate().cancel()
        photoView.visibility = View.VISIBLE
        photoView.showPlaceholder(bitmap, ProtonPreviewOrientation.of(bitmap))
        if (thumbnailPreview.isVisible) photoView.translationX = thumbnailPreview.translationX
        photoView.alpha = 1f
        clearThumbnailPreview()
        postDetailsSynchronization()
    }

    private fun showPhoto(uri: Uri) {
        val requestedStableId = request.stableId
        // The original is here: a preview still decoding would only be dropped on arrival.
        cancelPreviewLoad()
        resolvedUri = uri
        video.stop()
        playerView.visibility = View.GONE
        photoView.visibility = View.VISIBLE
        // The metadata read waits for the decode: the photo view's hints then spare it a second
        // bounds parse of the file, which with the sheet open used to happen on every swipe.
        photoView.load(uri) { result ->
            if (request.stableId != requestedStableId) return@load
            result
                .onSuccess {
                    navigationFallback = null
                    photoReady = true
                    hideLoadingPanel()
                    setActionsEnabled(true)
                    pendingZoomFactor?.let { zoom ->
                        pendingZoomFactor = null
                        photoView.restoreZoomFactor(zoom)
                    }
                    if (details.shown) details.ensureMetadataLoaded()
                    postDetailsSynchronization()
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
                    // An open sheet still gets its rows, read without hints.
                    if (details.shown) details.ensureMetadataLoaded()
                    handlePhotoLoadFailure(error, getString(R.string.could_not_display_photo))
                }
        }
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
    private fun beginNavigation(target: PhotoRequest) {
        val previousRequest = request
        photoTransitioning = true
        photoLoadJob?.cancel()
        cancelPreviewLoad()
        // A prefetch of the very photo the user is heading for is kept for loadPhoto to await;
        // one of the other neighbour is dropped, and the new photo's own prefetch restarts it in
        // the direction of travel once it is on screen.
        if (!ViewerPrefetchPolicy.isFor(prefetchStableId, target.stableId)) {
            prefetchJob?.cancel()
            prefetchJob = null
            prefetchStableId = null
        }
        cancelLoadingPanelDelay()
        navigationFallback = NavigationFallback(previousRequest, resolvedUri)
        setActionsEnabled(false)
    }

    /** Called by the swipe controller once the current media has slid away. */
    private fun commitNavigation(adjacent: PhotoRequest) {
        // A zoom or playback position kept for the restored photo has no business on its neighbour.
        pendingZoomFactor = null
        video.discardPendingPlayback()
        setCurrentRequest(adjacent)
        extendNavigationWindow()
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
        postDetailsSynchronization()
    }

    private fun resetPhotoStateForNavigation() {
        video.stop()
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
            video.stop()
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
        video.stop()
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
        // A sheet restored open waits for this: with no picture to attach to it would otherwise
        // stay at alpha 0 while still counting as shown, blocking swipes and eating the first Back.
        postDetailsSynchronization()
    }

    private var actionsEnabled = false

    private fun setActionsEnabled(enabled: Boolean) {
        actionsEnabled = enabled
        deleteButton.isEnabled = enabled && !mutationInFlight
        deleteButton.alpha = if (deleteButton.isEnabled) 1f else 0.45f
        updateFavoriteButton(enabled)
    }

    private fun updateFavoriteButton(enabled: Boolean = actionsEnabled) {
        favoriteButton.visibility = View.VISIBLE
        favoriteButton.isEnabled = enabled && !mutationInFlight
        favoriteButton.alpha = if (favoriteButton.isEnabled) 1f else 0.45f
        UiStyle.applyFavoriteIcon(favoriteButton, request.isFavorite)
    }

    /** The call runs in [mutationCoordinator]; its outcome comes back through [applyMutationOutcome]. */
    private fun toggleFavorite() {
        if (mutationCoordinator.setFavorite(UserId(request.userId), request, !request.isFavorite)) {
            updateFavoriteButton()
        }
    }

    /**
     * Decrypts the next photo's cached original ahead of a swipe so it opens from a ready file.
     * Only the direction of travel is prepared; on first open that is the forward neighbour, the
     * one most people swipe to first, so a single decrypt competes with the photo on screen.
     * Nothing is downloaded: only originals already in the encrypted cache qualify.
     */
    private fun prefetchAdjacentOriginals(stableId: String) {
        val offset = if (swipe.lastNavigationOffset == 0) 1 else swipe.lastNavigationOffset
        val neighbour = swipe.adjacentTo(stableId, offset)?.takeIf { it.mediaKind != MediaKind.VIDEO } ?: return
        prefetchJob?.cancel()
        prefetchStableId = neighbour.stableId
        prefetchJob =
            lifecycleScope.async(Dispatchers.IO) {
                // The decrypt watches this job between chunks, so a cancellation from
                // beginNavigation stops it promptly whether it has started or not.
                ensureActive()
                runCatching {
                    originalMedia.prepareCachedOriginal(UserId(neighbour.userId), neighbour.nodeUid)
                }.onFailure { error -> if (error is CancellationException) throw error }
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
        if (supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(TrashConfirmationDialogFragment.TAG) != null
        ) {
            return
        }
        TrashConfirmationDialogFragment
            .create(UserId(request.userId), listOf(request.nodeUid), singlePhoto = true)
            .show(supportFragmentManager, TrashConfirmationDialogFragment.TAG)
    }

    /**
     * The dialog may have been answered on a recreated activity; the photo it named is the one to
     * trash, and only under the account it was confirmed for.
     */
    override fun onTrashConfirmed(
        userId: UserId,
        nodeUids: List<String>,
    ) {
        val target = navigationRequests.firstOrNull { it.userId == userId.id && it.nodeUid in nodeUids } ?: return
        trashProtonPhoto(target)
    }

    /** The call runs in [mutationCoordinator]; its outcome comes back through [applyMutationOutcome]. */
    private fun trashProtonPhoto(target: PhotoRequest) {
        if (mutationCoordinator.trash(UserId(target.userId), target) && target.stableId == request.stableId) {
            setActionsEnabled(false)
        }
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
            scheduleMediaBoundsUpdate()
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

    private var mediaBoundsUpdatePending = false

    /**
     * The insets, the root layout and the action bar's layout all ask for the media bounds, often
     * in the same frame and from inside layout callbacks; one posted pass serves them all.
     */
    private fun scheduleMediaBoundsUpdate() {
        if (mediaBoundsUpdatePending) return
        mediaBoundsUpdatePending = true
        mediaFrame.post(mediaBoundsRunnable)
    }

    private val mediaBoundsRunnable =
        Runnable {
            mediaBoundsUpdatePending = false
            if (!destroyed) updateMediaBounds()
        }

    /** The views the vertical inset applies to; built once rather than on every bounds pass. */
    private val insetMediaViews: List<View> by lazy {
        listOf(photoView, thumbnailPreview, peekPreview, playerView, loadingPanel)
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
        var changed = false
        insetMediaViews.forEach { media ->
            val params = media.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin == inset && params.bottomMargin == inset) return@forEach
            params.topMargin = inset
            params.bottomMargin = inset
            media.layoutParams = params
            changed = true
        }
        if (!changed) return
        if (photoReady || thumbnailPreview.isVisible) postDetailsSynchronization()
    }

    private var detailsSynchronizationPending = false

    /**
     * Re-attaches the sheet on the next main-loop turn, once whatever asked for it has laid out.
     * Several callers ask in the same frame; one pass serves them all, and the runnable is a
     * field so onDestroy can withdraw it rather than let it touch a torn-down Activity.
     */
    private fun postDetailsSynchronization() {
        if (detailsSynchronizationPending) return
        detailsSynchronizationPending = true
        photoDetailsScroll.post(detailsSynchronizationRunnable)
    }

    private val detailsSynchronizationRunnable =
        Runnable {
            detailsSynchronizationPending = false
            if (!destroyed) details.synchronizeWithImage()
        }

    private fun clearThumbnailPreview() {
        video.cancelPendingPreviewClear()
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
        const val EXTRA_NAVIGATION_START = "com.bownee.lenswave.extra.NAVIGATION_START"
        const val EXTRA_NAVIGATION_TOKEN = "com.bownee.lenswave.extra.NAVIGATION_TOKEN"
        const val EXTRA_NAVIGATION_TOTAL = "com.bownee.lenswave.extra.NAVIGATION_TOTAL"
        const val EXTRA_SOURCE_DESTINATION = "com.bownee.lenswave.extra.SOURCE_DESTINATION"
        const val EXTRA_SOURCE_ALBUM_UID = "com.bownee.lenswave.extra.SOURCE_ALBUM_UID"
        const val EXTRA_SOURCE_ALBUM_NAME = "com.bownee.lenswave.extra.SOURCE_ALBUM_NAME"
        const val EXTRA_SOURCE_TAG = "com.bownee.lenswave.extra.SOURCE_TAG"
        private val RESULT_EXTRAS = listOf(EXTRA_PHOTO_DELETED, EXTRA_FAVORITE_CHANGED)
        private const val STATE_REQUEST = "viewer.request"
        private const val STATE_NAVIGATION = "viewer.navigation"
        private const val STATE_NAVIGATION_START = "viewer.navigation-start"
        private const val STATE_NAVIGATION_TOTAL = "viewer.navigation-total"
        private const val STATE_DETAILS_SHOWN = "viewer.details-shown"
        private const val STATE_ZOOM_FACTOR = "viewer.zoom-factor"
        private const val STATE_VIDEO_POSITION = "viewer.video-position"
        private const val STATE_VIDEO_PLAY_WHEN_READY = "viewer.video-play-when-ready"

        /** A zoom this close to fit is not worth re-applying over the clean fit the load produces. */
        private const val ZOOM_RESTORE_THRESHOLD = 1.05f
        private const val DETAILS_MINIMUM_HEIGHT_FRACTION = 0.55f
        private const val MEDIA_ACTION_GAP_DP = 8
        private const val VIDEO_CONTROLS_HEIGHT_DP = 80
        private const val FULL_QUALITY_CROSSFADE_MILLIS = 180L
        private const val LOADING_PANEL_DELAY_MILLIS = 300L

        /**
         * [destination] names the gallery page [navigation] came from, so a viewer restored after
         * a process death can rebuild the list from the page's cache (see
         * [PhotoNavigationSourceProvider]). [currentIndex] is where the gallery found [photo] in
         * [navigation]; when it is in range and names that photo the list is not searched, which
         * on a timeline of tens of thousands of entries is a scan the tap would otherwise pay for.
         * Any other value falls back to the search.
         */
        fun createIntent(
            context: Context,
            photo: GalleryAsset,
            userId: UserId,
            navigation: List<GalleryAsset> = listOf(photo),
            destination: GalleryDestination? = null,
            currentIndex: Int = -1,
        ): Intent {
            val request = PhotoRequest.from(photo, userId.id)
            val index = PhotoNavigationWindowPolicy.currentIndex(navigation, photo.stableId, currentIndex)
            val window = PhotoNavigationWindowPolicy.initial(index, navigation.size)
            // The intent carries a small window, parcelled directly; the full list stays in the
            // process and the viewer grows the window from it while the user swipes.
            return request.writeTo(Intent(context, PhotoViewerActivity::class.java)).apply {
                putParcelableArrayListExtra(
                    EXTRA_NAVIGATION,
                    navigation.subList(window.start, window.end).mapTo(ArrayList(window.size)) {
                        PhotoRequest.from(it, userId.id)
                    },
                )
                putExtra(EXTRA_NAVIGATION_START, window.start)
                putExtra(EXTRA_NAVIGATION_TOTAL, navigation.size)
                putExtra(EXTRA_NAVIGATION_TOKEN, PhotoNavigationSources.publish(userId.id, navigation))
                destination?.let { source ->
                    val stored = GalleryNavigationCodec.encode(source)
                    putExtra(EXTRA_SOURCE_DESTINATION, stored.destination)
                    putExtra(EXTRA_SOURCE_ALBUM_UID, stored.albumUid)
                    putExtra(EXTRA_SOURCE_ALBUM_NAME, stored.albumName)
                    putExtra(EXTRA_SOURCE_TAG, stored.tag)
                }
            }
        }

        private fun sourceDestination(intent: Intent): GalleryDestination? =
            GalleryNavigationCodec.decode(
                StoredGalleryNavigation(
                    destination = intent.getStringExtra(EXTRA_SOURCE_DESTINATION),
                    albumUid = intent.getStringExtra(EXTRA_SOURCE_ALBUM_UID),
                    albumName = intent.getStringExtra(EXTRA_SOURCE_ALBUM_NAME),
                    tag = intent.getStringExtra(EXTRA_SOURCE_TAG),
                ),
            )
    }
}
