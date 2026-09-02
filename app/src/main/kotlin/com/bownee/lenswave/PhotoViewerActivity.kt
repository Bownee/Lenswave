package com.bownee.lenswave

import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.DeviceCollection
import com.bownee.lenswave.gallery.PhotoDeletionDecision
import com.bownee.lenswave.gallery.PhotoDeletionOperation
import com.bownee.lenswave.gallery.PhotoDeletionPolicy
import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.PhotoReplica
import com.bownee.lenswave.gallery.PhotoSource
import com.bownee.lenswave.metadata.PhotoMetadataItem
import com.bownee.lenswave.metadata.PhotoMetadataAction
import com.bownee.lenswave.metadata.PhotoMetadataReader
import com.bownee.lenswave.metadata.requiresMediaLocationPermission
import com.bownee.lenswave.proton.ProtonPhotoGateway
import com.bownee.lenswave.storage.TransientPhotoFiles
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@AndroidEntryPoint
class PhotoViewerActivity : FragmentActivity() {
    @Inject lateinit var protonRepository: ProtonPhotoGateway
    @Inject lateinit var metadataReader: PhotoMetadataReader
    @Inject lateinit var photoDeletionExecutor: PhotoDeletionExecutor

    private lateinit var screen: PhotoViewerScreen
    private val root get() = screen.root
    private val backgroundScrim get() = screen.backgroundScrim
    private val photoDetailsScroll get() = screen.photoDetailsScroll
    private val photoDetailsSurface get() = screen.photoDetailsSurface
    private val mediaFrame get() = screen.mediaFrame
    private val thumbnailPreview get() = screen.thumbnailPreview
    private val photoView get() = screen.photoView
    private val loadingPanel get() = screen.loadingPanel
    private val status get() = screen.status
    private val progress get() = screen.progress
    private val retryButton get() = screen.retryButton
    private val backButton get() = screen.backButton
    private val actions get() = screen.actions
    private val editButton get() = screen.editButton
    private val deleteButton get() = screen.deleteButton
    private val detailsSheet get() = screen.detailsSheet
    private val detailsContent get() = screen.detailsContent
    private val detailsProgress get() = screen.detailsProgress
    private lateinit var request: PhotoRequest
    private var navigationRequests: List<PhotoRequest> = emptyList()
    private var resolvedUri: Uri? = null
    private var detailsShown = false
    private var metadataLoaded = false
    private var mediaLocationPermissionHandled = false
    private var photoTransitioning = false
    private var deletionInProgress = false
    private var transferredTransientPhoto = false
    private var dismissing = false
    private var photoReady = false
    private var thumbnailBitmap: Bitmap? = null
    private var navigationFallback: NavigationFallback? = null
    private var photoLoadJob: Job? = null
    private var loadingPanelRunnable: Runnable? = null
    private var detailsScrollAnimator: ValueAnimator? = null
    private var detailsDragStartOffset: Int? = null
    private var detailsDragStartedShown = false
    private var detailsSheetAttachmentOffset = 0
    private val verticalSettleInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val mediaLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        mediaLocationPermissionHandled = true
        if (!granted) {
            Toast.makeText(
                this,
                getString(R.string.location_permission_metadata_notice),
                Toast.LENGTH_LONG,
            ).show()
        }
        resolvedUri?.let(::loadMetadata)
    }

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            val uri = (request as? PhotoRequest.Device)?.uri?.toUri()
                ?: return@registerForActivityResult
            lifecycleScope.launch {
                val deleted = runCatching { photoDeletionExecutor.deleteDevice(uri) }.getOrDefault(0)
                if (deleted > 0) finishDeleted() else setActionsEnabled(true)
            }
            return@registerForActivityResult
        }
        finishDeleted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PhotoRequest.from(intent)
        navigationRequests = PhotoRequest.navigationFrom(intent).ifEmpty { listOf(request) }
        configureWindow()
        buildInterface()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        loadPhoto()
    }

    override fun onDestroy() {
        photoLoadJob?.cancel()
        detailsScrollAnimator?.cancel()
        cancelLoadingPanelDelay()
        clearThumbnailPreview()
        photoView.close()
        if (isFinishing && !transferredTransientPhoto) TransientPhotoFiles.deleteIfOwned(this, resolvedUri)
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildInterface() {
        screen = PhotoViewerScreen(
            context = this,
            requestIsTrashed = request.isTrashed,
            actions = PhotoViewerScreen.Actions(
                gesturesEnabled = {
                    this::screen.isInitialized &&
                        !photoTransitioning &&
                        !dismissing &&
                        photoView.isAtFitScale()
                },
                onVerticalDrag = ::handleDetailsDrag,
                onHorizontalDrag = ::handleHorizontalPhotoDrag,
                onBack = ::handleBack,
                onShowDetails = { showDetails() },
                onEdit = ::openEditor,
                onDelete = ::deletePhoto,
                onRetry = ::loadPhoto,
                onLayoutChanged = ::updatePhotoDetailsLayout,
            ),
        )
        setContentView(screen.root)
        applySystemInsets()
    }

    private fun updatePhotoDetailsLayout(availableHeight: Int) {
        if (availableHeight <= 0) return
        val mediaParams = mediaFrame.layoutParams as LinearLayout.LayoutParams
        if (mediaParams.height != availableHeight) {
            mediaParams.height = availableHeight
            mediaFrame.layoutParams = mediaParams
        }
        detailsSheet.minimumHeight = (availableHeight * DETAILS_MINIMUM_HEIGHT_FRACTION).roundToInt()
        photoDetailsScroll.post(::synchronizeDetailsSheetWithImage)
    }

    private fun loadPhoto() {
        photoLoadJob?.cancel()
        photoReady = false
        retryButton.visibility = View.GONE
        photoView.contentDescription = getString(
            R.string.photo_image_description,
            request.displayName.ifBlank { request.source.name.lowercase() },
        )
        scheduleLoadingPanel()
        val requestedPhoto = request
        if (requestedPhoto is PhotoRequest.Device) {
            clearThumbnailPreview()
            showPhoto(requestedPhoto.uri.toUri())
            return
        }
        requestedPhoto as PhotoRequest.Proton
        photoLoadJob = lifecycleScope.launch {
            showCachedProtonThumbnail(requestedPhoto)
            try {
                val (file, originalFileName) = withContext(Dispatchers.IO) {
                    val userId = UserId(requestedPhoto.userId)
                    val nodeUid = requestedPhoto.protonNodeUid
                    val original = protonRepository.downloadOriginal(
                        userId,
                        nodeUid,
                    )
                    val displayName = requestedPhoto.displayName.ifBlank {
                        protonRepository.getOriginalFileName(userId, nodeUid).orEmpty()
                    }
                    original to displayName
                }
                if (request.stableId != requestedPhoto.stableId) return@launch
                if (originalFileName.isNotBlank()) {
                    request = (request as PhotoRequest.Proton).copy(displayName = originalFileName)
                    request.writeTo(intent)
                }
                showPhoto(Uri.fromFile(file))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (request.stableId != requestedPhoto.stableId) return@launch
                handlePhotoLoadFailure(error, getString(R.string.could_not_download_proton_photo))
            }
        }
    }

    private suspend fun showCachedProtonThumbnail(requestedPhoto: PhotoRequest.Proton) {
        val bitmap = withContext(Dispatchers.IO) {
            protonRepository.readThumbnail(UserId(requestedPhoto.userId), requestedPhoto.protonNodeUid)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        if (!PhotoPreviewPolicy.canShow(
                requestedPhoto.source,
                requestedPhoto.stableId,
                request.stableId,
                bitmap != null,
            )
        ) {
            bitmap?.recycle()
            return
        }

        clearThumbnailPreview()
        thumbnailBitmap = requireNotNull(bitmap)
        thumbnailPreview.setImageBitmap(bitmap)
        thumbnailPreview.visibility = View.VISIBLE
        thumbnailPreview.animate().cancel()
        photoView.alpha = 0f
        thumbnailPreview.translationY = photoView.translationY
        thumbnailPreview.scaleX = photoView.scaleX
        thumbnailPreview.scaleY = photoView.scaleY
        thumbnailPreview.translationX = photoView.translationX
        thumbnailPreview.alpha = 0f
        thumbnailPreview.animate()
            .alpha(1f)
            .setDuration(120L)
            .start()
    }

    private fun showPhoto(uri: Uri) {
        val requestedStableId = request.stableId
        resolvedUri = uri
        if (detailsShown) ensureMetadataLoaded()
        photoView.load(uri) { result ->
            if (request.stableId != requestedStableId) return@load
            result.onSuccess {
                navigationFallback?.uri
                    ?.takeIf { it != uri }
                    ?.let { TransientPhotoFiles.deleteIfOwned(this, it) }
                navigationFallback = null
                photoReady = true
                hideLoadingPanel()
                setActionsEnabled(true)
                if (detailsShown) ensureMetadataLoaded()
                photoDetailsScroll.post {
                    if (request.stableId == requestedStableId) synchronizeDetailsSheetWithImage()
                }
                if (thumbnailPreview.isVisible) {
                    photoView.animate().cancel()
                    photoView.translationX = thumbnailPreview.translationX
                    photoView.alpha = 0f
                    photoView.animate()
                        .alpha(1f)
                        .setDuration(FULL_QUALITY_CROSSFADE_MILLIS)
                        .withEndAction { photoTransitioning = false }
                        .start()
                    thumbnailPreview.animate().cancel()
                    thumbnailPreview.animate()
                        .alpha(0f)
                        .setDuration(FULL_QUALITY_CROSSFADE_MILLIS)
                        .withEndAction(::clearThumbnailPreview)
                        .start()
                } else {
                    photoView.alpha = 1f
                }
            }.onFailure { error ->
                handlePhotoLoadFailure(error, getString(R.string.could_not_display_photo))
            }
        }
    }

    private fun scheduleLoadingPanel() {
        cancelLoadingPanelDelay()
        status.visibility = View.GONE
        progress.visibility = View.VISIBLE
        loadingPanel.visibility = View.GONE
        val requestedStableId = request.stableId
        val runnable = Runnable {
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

    private fun handleHorizontalPhotoDrag(distance: Float, finished: Boolean) {
        if (detailsShown || photoTransitioning || dismissing) return
        if (distance == 0f) {
            if (finished) resetHorizontalPhotoDrag()
            return
        }

        val offset = if (distance > 0f) 1 else -1
        if (adjacentTo(request.stableId, offset) == null) {
            resetHorizontalPhotoDrag()
            return
        }
        if (!finished) {
            cancelMediaAnimations()
            setMediaTranslationX(-distance.coerceIn(-root.width.toFloat(), root.width.toFloat()))
            return
        }

        val threshold = max(root.width * 0.1f, dp(40).toFloat())
        if (abs(distance) >= threshold) navigatePhoto(offset) else resetHorizontalPhotoDrag()
    }

    private fun resetHorizontalPhotoDrag() {
        cancelMediaAnimations()
        animateMediaTranslationX(0f, 160L)
    }

    private fun navigatePhoto(offset: Int) {
        if (detailsShown || photoTransitioning || dismissing) return
        val adjacent = adjacentTo(request.stableId, offset) ?: return

        val previousRequest = request
        photoTransitioning = true
        photoLoadJob?.cancel()
        cancelLoadingPanelDelay()
        navigationFallback = NavigationFallback(previousRequest, resolvedUri)
        setActionsEnabled(false)
        cancelMediaAnimations()
        photoView.animate()
            .translationX(-offset * root.width.toFloat())
            .setDuration(160L)
            .withEndAction {
                thumbnailPreview.animate().cancel()
                loadingPanel.animate().cancel()
                request = adjacent
                request.writeTo(intent)
                resetPhotoStateForNavigation()
                photoTransitioning = false
                loadPhoto()
            }
            .start()
        thumbnailPreview.animate()
            .translationX(-offset * root.width.toFloat())
            .setDuration(160L)
            .start()
        loadingPanel.animate()
            .translationX(-offset * root.width.toFloat())
            .setDuration(160L)
            .start()
    }

    private fun resetPhotoStateForNavigation() {
        clearThumbnailPreview()
        photoView.clear()
        resolvedUri = null
        photoReady = false
        metadataLoaded = false
        mediaLocationPermissionHandled = false
        detailsContent.removeAllViews()
        detailsProgress.visibility = View.VISIBLE
        detailsSheetAttachmentOffset = 0
        detailsSheet.translationY = 0f
        detailsSheet.alpha = 0f
        detailsSheet.visibility = View.INVISIBLE
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
    }

    private fun handlePhotoLoadFailure(error: Throwable, fallbackMessage: String) {
        val fallback = navigationFallback
        navigationFallback = null
        if (fallback != null) {
            clearThumbnailPreview()
            request = fallback.request
            request.writeTo(intent)
            resolvedUri = null
            photoTransitioning = false
            photoView.translationX = 0f
            photoView.alpha = 1f
            Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show()
            if (fallback.uri != null) {
                scheduleLoadingPanel()
                showPhoto(fallback.uri)
            } else {
                loadPhoto()
            }
            return
        }
        photoTransitioning = false
        photoView.translationX = 0f
        photoView.alpha = 1f
        cancelLoadingPanelDelay()
        loadingPanel.translationX = 0f
        loadingPanel.visibility = View.VISIBLE
        progress.visibility = View.GONE
        status.text = fallbackMessage
        status.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
    }

    private fun setActionsEnabled(enabled: Boolean) {
        editButton.isEnabled = enabled && !request.isTrashed
        deleteButton.isEnabled = enabled
        editButton.alpha = if (editButton.isEnabled) 1f else 0.45f
        deleteButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun adjacentTo(stableId: String, offset: Int): PhotoRequest? {
        val index = navigationRequests.indexOfFirst { it.stableId == stableId }
        return if (index < 0) null else navigationRequests.getOrNull(index + offset)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { navigatePhoto(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { navigatePhoto(1); true }
        KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
            photoView.zoomIn(); true
        }
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { photoView.zoomOut(); true }
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { photoView.resetZoom(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun loadMetadata(uri: Uri) {
        if (metadataLoaded) return
        metadataLoaded = true
        val metadataRequest = request
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    metadataReader.read(
                        this@PhotoViewerActivity,
                        uri,
                        metadataRequest.source,
                        metadataRequest.displayName,
                        metadataRequest.capturedAt,
                        (metadataRequest as? PhotoRequest.Device)
                            ?.protonBackingNodeUids
                            ?.isNotEmpty() == true,
                    )
                }
            }
            if (request.stableId != metadataRequest.stableId) return@launch
            detailsProgress.visibility = View.GONE
            result.onSuccess { items -> items.forEach(::addDetailsRow) }
                .onFailure {
                    addDetailsRow(PhotoMetadataItem(getString(R.string.error), getString(R.string.could_not_read_metadata)))
                }
        }
    }

    private fun addDetailsRow(item: PhotoMetadataItem) {
        detailsContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addView(TextView(this@PhotoViewerActivity).apply {
                text = item.label
                textSize = 11f
                setTextColor(UiStyle.muted)
            })
            addView(TextView(this@PhotoViewerActivity).apply {
                text = item.value
                textSize = 15f
                setTextColor(UiStyle.text)
                setPadding(0, dp(2), 0, 0)
            })
            if (item.action is PhotoMetadataAction.OpenMap) {
                addView(TextView(this@PhotoViewerActivity).apply {
                    setText(R.string.open_in_maps)
                    textSize = 13f
                    setTextColor(UiStyle.accent)
                    setPadding(0, dp(7), 0, dp(2))
                    setOnClickListener { openMap(item.action) }
                })
                setOnClickListener { openMap(item.action) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        })
    }

    private fun handleDetailsDrag(distance: Float, velocity: Float, finished: Boolean) {
        if (photoTransitioning || dismissing) return
        if (!detailsShown && detailsDragStartOffset == null && distance < 0f) {
            handlePhotoDismissDrag(-distance, -velocity, finished)
            return
        }

        if (!finished) {
            val startOffset = detailsDragStartOffset ?: photoDetailsScroll.scrollY.also {
                detailsDragStartOffset = it
                detailsDragStartedShown = detailsShown
                detailsScrollAnimator?.cancel()
            }
            setDetailsOffset((startOffset + distance).roundToInt())
            return
        }

        val startOffset = detailsDragStartOffset
        val startedShown = detailsDragStartedShown
        detailsDragStartOffset = null
        detailsDragStartedShown = false
        if (startOffset == null) {
            if (!detailsShown) resetPhotoDismiss()
            return
        }

        if (!startedShown) {
            val initialOffset = initialDetailsOffset().toFloat()
            if (VerticalGesturePolicy.shouldSettleSheet(
                distance,
                velocity,
                initialOffset,
                resources.displayMetrics.density,
            )
            ) {
                showDetails(velocity)
            } else {
                hideDetails(velocity)
            }
            return
        }

        val initialOffset = initialDetailsOffset()
        val targetOffset = VerticalGesturePolicy.detailsSettleOffset(
            currentOffset = photoDetailsScroll.scrollY,
            velocity = velocity,
            initialOffset = initialOffset,
            maximumOffset = maximumDetailsOffset(),
        )
        if (targetOffset == 0) hideDetails(velocity) else animateDetailsOffset(targetOffset, velocity)
    }

    private fun handlePhotoDismissDrag(distance: Float, velocity: Float, finished: Boolean) {
        if (photoTransitioning || dismissing) return
        if (finished) {
            if (VerticalGesturePolicy.shouldDismissViewer(
                    distance,
                    velocity,
                    root.height.toFloat(),
                    resources.displayMetrics.density,
                )
            ) {
                animateDismissToGallery(velocity)
            } else {
                resetPhotoDismiss(velocity)
            }
            return
        }

        cancelMediaAnimations()
        backgroundScrim.animate().cancel()
        backButton.animate().cancel()
        actions.animate().cancel()
        val progress = (distance / (root.height * 0.58f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val photoScale = 1f - 0.12f * progress
        setMediaDismissTransform(distance, photoScale)
        loadingPanel.alpha = 1f - progress
        backgroundScrim.alpha = 1f - 0.88f * progress
        backButton.alpha = 1f - progress
        actions.alpha = 1f - progress
    }

    private fun resetPhotoDismiss(velocity: Float = 0f) {
        if (dismissing) return
        val duration = verticalSettleDuration(photoView.translationY, velocity)
        cancelMediaAnimations()
        animateMediaDismissTransform(0f, 1f, 1f, duration)
        backgroundScrim.animate().alpha(1f).setDuration(duration).setInterpolator(verticalSettleInterpolator).start()
        backButton.animate().alpha(1f).setDuration(duration).setInterpolator(verticalSettleInterpolator).start()
        actions.animate().alpha(1f).setDuration(duration).setInterpolator(verticalSettleInterpolator).start()
    }

    @Suppress("DEPRECATION")
    private fun animateDismissToGallery(velocity: Float = 0f) {
        dismissing = true
        setActionsEnabled(false)
        val targetY = (root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 0.9f
        val duration = verticalSettleDuration(targetY - photoView.translationY, velocity)
        cancelMediaAnimations()
        photoView.animate()
            .translationY(targetY)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .alpha(0.12f)
            .setDuration(duration)
            .setInterpolator(verticalSettleInterpolator)
            .withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
        thumbnailPreview.animate()
            .translationY(targetY)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .alpha(0.12f)
            .setDuration(duration)
            .setInterpolator(verticalSettleInterpolator)
            .start()
        loadingPanel.animate()
            .translationY(targetY)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(verticalSettleInterpolator)
            .start()
        if (detailsShown) {
            detailsSheet.animate().cancel()
            detailsSheet.animate()
                .translationY(detailsSheet.height.toFloat())
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(verticalSettleInterpolator)
                .start()
        }
        backgroundScrim.animate().alpha(0f).setDuration(duration).setInterpolator(verticalSettleInterpolator).start()
        backButton.animate().alpha(0f).setDuration(duration).setInterpolator(verticalSettleInterpolator).start()
        actions.animate().alpha(0f).setDuration(duration).setInterpolator(verticalSettleInterpolator).start()
    }

    private fun showDetails(velocity: Float = 0f) {
        ensureMetadataLoaded()
        detailsShown = true
        detailsSheet.visibility = View.VISIBLE
        actions.animate().cancel()
        animateDetailsOffset(initialDetailsOffset(), velocity)
    }

    private fun hideDetails(velocity: Float = 0f) {
        detailsShown = false
        actions.visibility = View.VISIBLE
        actions.animate().cancel()
        animateDetailsOffset(0, velocity)
    }

    private fun setDetailsOffset(offset: Int) {
        val boundedOffset = offset.coerceIn(0, maximumDetailsOffset())
        photoDetailsScroll.scrollTo(0, boundedOffset)
        val initialOffset = initialDetailsOffset().coerceAtLeast(1)
        val progress = (boundedOffset.toFloat() / initialOffset).coerceIn(0f, 1f)
        detailsSheet.alpha = progress
        detailsSheet.visibility = if (progress > 0f || detailsShown) View.VISIBLE else View.INVISIBLE
        if (progress < 1f) actions.visibility = View.VISIBLE
        actions.alpha = 1f - progress
        if (progress >= 1f && detailsShown) actions.visibility = View.INVISIBLE
    }

    private fun animateDetailsOffset(targetOffset: Int, velocity: Float) {
        detailsScrollAnimator?.cancel()
        val boundedTarget = targetOffset.coerceIn(0, maximumDetailsOffset())
        val startOffset = photoDetailsScroll.scrollY
        val duration = verticalSettleDuration((boundedTarget - startOffset).toFloat(), velocity)
        detailsScrollAnimator = ValueAnimator.ofInt(startOffset, boundedTarget).apply {
            this.duration = duration
            interpolator = verticalSettleInterpolator
            addUpdateListener { animator -> setDetailsOffset(animator.animatedValue as Int) }
            start()
        }
    }

    private fun initialDetailsOffset(): Int = PhotoDetailsLayoutPolicy.initialOffset(
        mediaHeight = mediaFrame.height,
        fittedImageBottom = photoView.fittedImageBottom()?.plus(photoView.top),
        overlap = resources.getDimensionPixelSize(R.dimen.photo_details_sheet_overlap),
        fallbackOffset = (root.height * DETAILS_FALLBACK_OFFSET_FRACTION).roundToInt(),
        maximumOffset = maximumDetailsOffset(),
    )

    private fun updateDetailsSheetAttachment(): Int {
        val previousOffset = detailsSheetAttachmentOffset
        detailsSheetAttachmentOffset = PhotoDetailsLayoutPolicy.attachmentOffset(
            mediaHeight = mediaFrame.height,
            fittedImageBottom = photoView.fittedImageBottom()?.plus(photoView.top),
            overlap = resources.getDimensionPixelSize(R.dimen.photo_details_sheet_overlap),
        )
        detailsSheet.translationY = -detailsSheetAttachmentOffset.toFloat()
        return detailsSheetAttachmentOffset - previousOffset
    }

    private fun synchronizeDetailsSheetWithImage() {
        val attachmentChange = updateDetailsSheetAttachment()
        val adjustedOffset = photoDetailsScroll.scrollY - attachmentChange
        val target = if (detailsShown) {
            adjustedOffset.coerceAtLeast(initialDetailsOffset())
        } else {
            0
        }
        setDetailsOffset(target.coerceAtMost(maximumDetailsOffset()))
    }

    private fun maximumDetailsOffset(): Int {
        val surfaceHeight = photoDetailsScroll.getChildAt(0)?.measuredHeight ?: return 0
        return PhotoDetailsLayoutPolicy.maximumOffset(
            surfaceHeight = surfaceHeight,
            viewportHeight = photoDetailsScroll.height,
            attachmentOffset = detailsSheetAttachmentOffset,
        )
    }

    private fun cancelMediaAnimations() {
        photoView.animate().cancel()
        thumbnailPreview.animate().cancel()
        loadingPanel.animate().cancel()
    }

    private fun setMediaTranslationX(translationX: Float) {
        photoView.translationX = translationX
        thumbnailPreview.translationX = translationX
        loadingPanel.translationX = translationX
    }

    private fun animateMediaTranslationX(translationX: Float, duration: Long) {
        photoView.animate().translationX(translationX).setDuration(duration).start()
        thumbnailPreview.animate().translationX(translationX).setDuration(duration).start()
        loadingPanel.animate().translationX(translationX).setDuration(duration).start()
    }

    private fun setMediaTranslationY(translationY: Float) {
        photoView.translationY = translationY
        thumbnailPreview.translationY = translationY
        loadingPanel.translationY = translationY
    }

    private fun setMediaDismissTransform(translationY: Float, scale: Float) {
        setMediaTranslationY(translationY)
        photoView.scaleX = scale
        photoView.scaleY = scale
        thumbnailPreview.scaleX = scale
        thumbnailPreview.scaleY = scale
    }

    private fun animateMediaDismissTransform(
        translationY: Float,
        scale: Float,
        alpha: Float,
        duration: Long,
    ) {
        photoView.animate()
            .translationY(translationY)
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(verticalSettleInterpolator)
            .start()
        thumbnailPreview.animate()
            .translationY(translationY)
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(verticalSettleInterpolator)
            .start()
        loadingPanel.animate()
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(verticalSettleInterpolator)
            .start()
    }

    private fun verticalSettleDuration(remainingDistance: Float, velocity: Float): Long {
        val absoluteDistance = abs(remainingDistance)
        val absoluteVelocity = abs(velocity)
        if (absoluteDistance < 1f) return 0L
        if (absoluteVelocity < dp(200)) return 260L
        return (absoluteDistance / absoluteVelocity * 800f)
            .roundToLong()
            .coerceIn(140L, 260L)
    }

    private fun handleBack() {
        if (detailsShown) hideDetails() else finish()
    }

    private fun ensureMetadataLoaded() {
        if (metadataLoaded) return
        val uri = resolvedUri ?: return
        if (!mediaLocationPermissionHandled &&
            requiresMediaLocationPermission(this, uri, request.source)
        ) {
            mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        loadMetadata(uri)
    }

    private fun openMap(action: PhotoMetadataAction.OpenMap) {
        val coordinates = "${action.latitude},${action.longitude}"
        val intent = Intent(
            Intent.ACTION_VIEW,
            "geo:$coordinates?q=$coordinates".toUri(),
        )
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    private fun openEditor() {
        val uri = resolvedUri ?: return
        transferredTransientPhoto = true
        startActivity(Intent(this, PhotoEditorActivity::class.java).apply {
            putExtra(PhotoEditorActivity.EXTRA_PHOTO_URI, uri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        finish()
    }

    private fun deletePhoto() {
        val decision = PhotoDeletionPolicy.decide(
            targets = listOf(request.toPhotoTarget()),
            permanently = request.isTrashed,
        ) as? PhotoDeletionDecision.Allowed ?: return
        when (decision.plan.operation) {
            PhotoDeletionOperation.DELETE_PERMANENTLY -> when (decision.plan.source) {
                PhotoSource.DEVICE -> confirmDeleteDevicePhotoPermanently()
                PhotoSource.PROTON -> confirmDeleteProtonPhotoPermanently()
            }
            PhotoDeletionOperation.MOVE_TO_TRASH -> when (decision.plan.source) {
                PhotoSource.DEVICE -> deleteDevicePhoto()
                PhotoSource.PROTON -> confirmTrashProtonPhoto()
            }
        }
    }

    private fun deleteDevicePhoto() {
        val uri = (request as? PhotoRequest.Device)?.uri?.toUri() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createTrashRequest(contentResolver, listOf(uri), true)
            deleteConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo_question)
            .setMessage(R.string.delete_device_photo_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteAndroidTenPhoto(uri) }
            .show()
    }

    private fun deleteAndroidTenPhoto(uri: Uri) {
        setActionsEnabled(false)
        lifecycleScope.launch {
            try {
                val deleted = photoDeletionExecutor.deleteDevice(uri)
                if (deleted > 0) finishDeleted() else setActionsEnabled(true)
            } catch (error: RecoverableSecurityException) {
                deleteConsentLauncher.launch(
                    IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
                )
            } catch (_: Throwable) {
                setActionsEnabled(true)
                Toast.makeText(this@PhotoViewerActivity, R.string.delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteDevicePhotoPermanently() {
        val uri = (request as? PhotoRequest.Device)?.uri?.toUri() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo_permanently_question)
            .setMessage(R.string.delete_device_trash_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ ->
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
            .show()
    }

    private fun confirmTrashProtonPhoto() {
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_proton_trash_question)
            .setMessage(R.string.recover_from_proton_trash)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.move_to_trash) { _, _ -> trashProtonPhoto() }
            .show()
    }

    private fun trashProtonPhoto() {
        if (deletionInProgress) return
        val requestedPhoto = request as? PhotoRequest.Proton ?: return
        val userId = UserId(requestedPhoto.userId)
        val nodeUid = requestedPhoto.protonNodeUid
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
                    Toast.makeText(this@PhotoViewerActivity, R.string.could_not_move_to_proton_trash, Toast.LENGTH_LONG).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                deletionInProgress = false
                setActionsEnabled(true)
                Toast.makeText(this@PhotoViewerActivity, R.string.could_not_move_to_proton_trash, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteProtonPhotoPermanently() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo_permanently_question)
            .setMessage(R.string.delete_proton_trash_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ -> deleteProtonPhotoPermanently() }
            .show()
    }

    private fun deleteProtonPhotoPermanently() {
        if (deletionInProgress) return
        val requestedPhoto = request as? PhotoRequest.Proton ?: return
        val userId = UserId(requestedPhoto.userId)
        val nodeUid = requestedPhoto.protonNodeUid
        deletionInProgress = true
        setActionsEnabled(false)
        lifecycleScope.launch {
            try {
                val result = photoDeletionExecutor.deleteProtonPermanently(userId, listOf(nodeUid))
                if (result.successfulCount == 1) {
                    finishDeleted()
                } else {
                    deletionInProgress = false
                    setActionsEnabled(true)
                    Toast.makeText(
                        this@PhotoViewerActivity,
                        getString(R.string.could_not_permanently_delete_photo),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                deletionInProgress = false
                setActionsEnabled(true)
                Toast.makeText(
                    this@PhotoViewerActivity,
                    getString(R.string.could_not_permanently_delete_photo),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun finishDeleted() {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PHOTO_DELETED, true))
        finish()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeArea: Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (backButton.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = dp(8) + if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    safeArea.right
                } else {
                    safeArea.left
                }
                topMargin = dp(8) + safeArea.top
                backButton.layoutParams = this
            }
            (actions.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = dp(8) + safeArea.left
                rightMargin = dp(8) + safeArea.right
                bottomMargin = dp(8) + safeArea.bottom
                actions.layoutParams = this
            }
            (photoView.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = dp(PHOTO_TOP_MARGIN_DP) + safeArea.top
                bottomMargin = dp(PHOTO_BOTTOM_MARGIN_DP) + safeArea.bottom
                photoView.layoutParams = this
            }
            (thumbnailPreview.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = dp(PHOTO_TOP_MARGIN_DP) + safeArea.top
                bottomMargin = dp(PHOTO_BOTTOM_MARGIN_DP) + safeArea.bottom
                thumbnailPreview.layoutParams = this
            }
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

    private fun clearThumbnailPreview() {
        thumbnailPreview.animate().cancel()
        thumbnailPreview.setImageDrawable(null)
        thumbnailPreview.visibility = View.GONE
        thumbnailPreview.alpha = 0f
        thumbnailPreview.translationX = 0f
        thumbnailPreview.translationY = 0f
        thumbnailPreview.scaleX = 1f
        thumbnailPreview.scaleY = 1f
        thumbnailBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        thumbnailBitmap = null
    }

    private data class NavigationFallback(
        val request: PhotoRequest,
        val uri: Uri?,
    )

    companion object {
        const val EXTRA_SOURCE = "com.bownee.lenswave.extra.SOURCE"
        const val EXTRA_URI = "com.bownee.lenswave.extra.URI"
        const val EXTRA_PROTON_NODE_UID = "com.bownee.lenswave.extra.PROTON_NODE_UID"
        const val EXTRA_PROTON_BACKING_NODE_UIDS = "com.bownee.lenswave.extra.PROTON_BACKING_NODE_UIDS"
        const val EXTRA_USER_ID = "com.bownee.lenswave.extra.USER_ID"
        const val EXTRA_CAPTURED_AT = "com.bownee.lenswave.extra.CAPTURED_AT"
        const val EXTRA_DISPLAY_NAME = "com.bownee.lenswave.extra.DISPLAY_NAME"
        const val EXTRA_STABLE_ID = "com.bownee.lenswave.extra.STABLE_ID"
        const val EXTRA_IS_TRASHED = "com.bownee.lenswave.extra.IS_TRASHED"
        const val EXTRA_PHOTO_DELETED = "com.bownee.lenswave.extra.PHOTO_DELETED"
        const val EXTRA_NAVIGATION = "com.bownee.lenswave.extra.NAVIGATION"
        private const val DETAILS_MINIMUM_HEIGHT_FRACTION = 0.55f
        private const val DETAILS_FALLBACK_OFFSET_FRACTION = 0.55f
        private const val PHOTO_TOP_MARGIN_DP = 64
        private const val PHOTO_BOTTOM_MARGIN_DP = 112
        private const val FULL_QUALITY_CROSSFADE_MILLIS = 180L
        private const val LOADING_PANEL_DELAY_MILLIS = 300L

        fun createIntent(
            context: Context,
            photo: GalleryAsset,
            userId: UserId?,
            navigation: List<GalleryAsset> = listOf(photo),
        ): Intent {
            val request = PhotoRequest.from(photo, userId?.id)
            val currentIndex = navigation.indexOfFirst { it.stableId == photo.stableId }.coerceAtLeast(0)
            val from = (currentIndex - NAVIGATION_RADIUS).coerceAtLeast(0)
            val to = (currentIndex + NAVIGATION_RADIUS + 1).coerceAtMost(navigation.size)
            return request.writeTo(Intent(context, PhotoViewerActivity::class.java)).apply {
                putParcelableArrayListExtra(
                    EXTRA_NAVIGATION,
                    ArrayList(navigation.subList(from, to).map {
                        PhotoRequest.from(it, userId?.id).toBundle()
                    }),
                )
            }
        }

        private const val NAVIGATION_RADIUS = 100
    }
}
