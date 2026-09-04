package com.bownee.lenswave.viewer

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import com.bownee.lenswave.R
import com.bownee.lenswave.metadata.PhotoMetadataAction
import com.bownee.lenswave.metadata.PhotoMetadataHints
import com.bownee.lenswave.metadata.PhotoMetadataItem
import com.bownee.lenswave.metadata.PhotoMetadataReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The details sheet below the media: its scroll offset, how it attaches to the bottom of the
 * fitted image, the drag that reveals it, and the metadata rows it shows. Upward drags that start
 * with the sheet closed are handed back to the host as dismiss drags.
 */
internal class ViewerDetailsSheetController(
    private val context: Context,
    private val screen: PhotoViewerScreen,
    private val metadataReader: PhotoMetadataReader,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    internal interface Host {
        val request: PhotoRequest
        val resolvedUri: Uri?

        /** True while a photo transition or a dismiss is already running. */
        val gesturesBlocked: Boolean

        /** Bottom edge of the fitted media inside the media frame, if it is known yet. */
        fun fittedMediaBottom(): Float?

        /** What the photo view already decoded from [uri], so the reader need not open it again. */
        fun metadataHints(uri: Uri): PhotoMetadataHints?

        fun handlePhotoDismissDrag(
            distance: Float,
            velocity: Float,
            finished: Boolean,
        )

        fun resetPhotoDismiss()
    }

    private val root get() = screen.root
    private val photoDetailsScroll get() = screen.photoDetailsScroll
    private val mediaFrame get() = screen.mediaFrame
    private val mediaTitle get() = screen.mediaTitle
    private val actions get() = screen.actions
    private val detailsSheet get() = screen.detailsSheet
    private val detailsProgress get() = screen.detailsProgress
    private val resources get() = context.resources

    var shown = false
        private set

    /** True once the rows for the current photo were read successfully. */
    private var metadataLoaded = false
    private var metadataJob: Job? = null
    private var detailsScrollAnimator: ValueAnimator? = null
    private var detailsDragStartOffset: Int? = null
    private var detailsDragStartedShown = false
    private var detailsSheetAttachmentOffset = 0

    /** Resolved once: a resource lookup per animation frame is a needless trip through the resource table. */
    private val sheetOverlap = resources.getDimensionPixelSize(R.dimen.photo_details_sheet_overlap)

    /**
     * The offsets a drag or settle animation moves between, fixed when it starts. The fitted
     * image, the sheet's measured height and the attachment do not change while the finger or
     * the animator drives the offset, so recomputing them for every frame only cost time.
     */
    private var motionInitialOffset = 0
    private var motionMaximumOffset = 0

    fun handleDetailsDrag(
        distance: Float,
        velocity: Float,
        finished: Boolean,
    ) {
        if (host.gesturesBlocked) return
        if (!shown && detailsDragStartOffset == null && distance < 0f) {
            host.handlePhotoDismissDrag(-distance, -velocity, finished)
            return
        }

        if (!finished) {
            val startOffset =
                detailsDragStartOffset ?: photoDetailsScroll.scrollY.also {
                    detailsDragStartOffset = it
                    detailsDragStartedShown = shown
                    detailsScrollAnimator?.cancel()
                    beginMotion()
                }
            setDetailsOffset((startOffset + distance).roundToInt(), motionInitialOffset, motionMaximumOffset)
            return
        }

        val startOffset = detailsDragStartOffset
        val startedShown = detailsDragStartedShown
        detailsDragStartOffset = null
        detailsDragStartedShown = false
        if (startOffset == null) {
            if (!shown) host.resetPhotoDismiss()
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
        val targetOffset =
            VerticalGesturePolicy.detailsSettleOffset(
                currentOffset = photoDetailsScroll.scrollY,
                velocity = velocity,
                initialOffset = initialOffset,
                maximumOffset = maximumDetailsOffset(),
            )
        if (targetOffset == 0) hideDetails(velocity) else animateDetailsOffset(targetOffset, velocity)
    }

    fun showDetails(velocity: Float = 0f) {
        ensureMetadataLoaded()
        shown = true
        detailsSheet.visibility = View.VISIBLE
        actions.animate().cancel()
        animateDetailsOffset(initialDetailsOffset(), velocity)
    }

    fun hideDetails(velocity: Float = 0f) {
        shown = false
        actions.visibility = View.VISIBLE
        actions.animate().cancel()
        animateDetailsOffset(0, velocity)
    }

    /**
     * Marks the sheet open before anything is laid out, for a recreated viewer whose sheet was
     * open: [synchronizeWithImage] then settles it against the image once that is on screen,
     * and the rows load with the photo like they do for any open sheet.
     */
    fun restoreShown() {
        shown = true
        detailsSheet.visibility = View.VISIBLE
    }

    /** Fixes the offsets a drag or animation will move between. */
    private fun beginMotion() {
        motionMaximumOffset = maximumDetailsOffset()
        motionInitialOffset = initialDetailsOffset()
    }

    private fun setDetailsOffset(
        offset: Int,
        initialOffset: Int,
        maximumOffset: Int,
    ) {
        val boundedOffset = offset.coerceIn(0, maximumOffset)
        photoDetailsScroll.scrollTo(0, boundedOffset)
        val sheetProgress = (boundedOffset.toFloat() / initialOffset.coerceAtLeast(1)).coerceIn(0f, 1f)
        detailsSheet.alpha = sheetProgress
        detailsSheet.visibility = if (sheetProgress > 0f || shown) View.VISIBLE else View.INVISIBLE
        if (sheetProgress < 1f) actions.visibility = View.VISIBLE
        actions.alpha = 1f - sheetProgress
        mediaTitle.alpha = 1f - sheetProgress
        if (sheetProgress >= 1f && shown) actions.visibility = View.INVISIBLE
    }

    private fun animateDetailsOffset(
        targetOffset: Int,
        velocity: Float,
    ) {
        detailsScrollAnimator?.cancel()
        beginMotion()
        val initialOffset = motionInitialOffset
        val maximumOffset = motionMaximumOffset
        val boundedTarget = targetOffset.coerceIn(0, maximumOffset)
        val startOffset = photoDetailsScroll.scrollY
        val duration =
            ViewerVerticalSettle.duration(
                (boundedTarget - startOffset).toFloat(),
                velocity,
                resources.displayMetrics.density,
            )
        detailsScrollAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = ViewerVerticalSettle.interpolator
                addUpdateListener { animator ->
                    // Interpolated by hand from the fraction: animatedValue would box an Int per frame.
                    val offset = startOffset + ((boundedTarget - startOffset) * animator.animatedFraction).roundToInt()
                    setDetailsOffset(offset, initialOffset, maximumOffset)
                }
                start()
            }
    }

    private fun initialDetailsOffset(): Int = initialDetailsOffset(maximumDetailsOffset())

    private fun initialDetailsOffset(maximumOffset: Int): Int =
        PhotoDetailsLayoutPolicy.initialOffset(
            mediaHeight = mediaFrame.height,
            fittedImageBottom = host.fittedMediaBottom(),
            overlap = sheetOverlap,
            fallbackOffset = (root.height * DETAILS_FALLBACK_OFFSET_FRACTION).roundToInt(),
            maximumOffset = maximumOffset,
        )

    private fun updateDetailsSheetAttachment(): Int {
        val previousOffset = detailsSheetAttachmentOffset
        detailsSheetAttachmentOffset =
            PhotoDetailsLayoutPolicy.attachmentOffset(
                mediaHeight = mediaFrame.height,
                fittedImageBottom = host.fittedMediaBottom(),
                overlap = sheetOverlap,
            )
        detailsSheet.translationY = -detailsSheetAttachmentOffset.toFloat()
        return detailsSheetAttachmentOffset - previousOffset
    }

    /** Re-attaches the sheet to the fitted image and keeps the current scroll position sensible. */
    fun synchronizeWithImage() {
        val attachmentChange = updateDetailsSheetAttachment()
        val adjustedOffset = photoDetailsScroll.scrollY - attachmentChange
        val maximumOffset = maximumDetailsOffset()
        val initialOffset = initialDetailsOffset(maximumOffset)
        val target = if (shown) adjustedOffset.coerceAtLeast(initialOffset) else 0
        setDetailsOffset(target.coerceAtMost(maximumOffset), initialOffset, maximumOffset)
    }

    private fun maximumDetailsOffset(): Int {
        val surfaceHeight = photoDetailsScroll.getChildAt(0)?.measuredHeight ?: return 0
        return PhotoDetailsLayoutPolicy.maximumOffset(
            surfaceHeight = surfaceHeight,
            viewportHeight = photoDetailsScroll.height,
            attachmentOffset = detailsSheetAttachmentOffset,
        )
    }

    /** Clears the rows and the attachment for the next photo; `shown` is deliberately kept. */
    fun resetForNavigation() {
        metadataLoaded = false
        metadataJob?.cancel()
        metadataJob = null
        screen.hideDetailsRows()
        detailsProgress.visibility = View.VISIBLE
        detailsSheetAttachmentOffset = 0
        detailsSheet.translationY = 0f
        detailsSheet.alpha = 0f
        detailsSheet.visibility = View.INVISIBLE
    }

    /**
     * Reads the rows for the current photo unless they are already there or being read. A read
     * that failed does not latch, so the next call (the original arriving, the sheet reopening)
     * tries again.
     */
    fun ensureMetadataLoaded() {
        if (metadataLoaded || metadataJob?.isActive == true) return
        val uri = host.resolvedUri ?: return
        loadMetadata(uri)
    }

    private fun loadMetadata(uri: Uri) {
        val metadataRequest = host.request
        metadataJob =
            scope.launch {
                // Resolved here rather than at the call: by the time this coroutine runs the
                // photo view may have finished its decode, and its hints spare the reader a
                // second bounds parse of the file.
                val hints = host.metadataHints(uri)
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            metadataReader.read(
                                context,
                                uri,
                                metadataRequest.displayName,
                                metadataRequest.capturedAt,
                                hints,
                            )
                        }
                    }
                if (host.request.stableId != metadataRequest.stableId) return@launch
                detailsProgress.visibility = View.GONE
                val items =
                    result.getOrElse {
                        listOf(
                            PhotoMetadataItem(
                                context.getString(R.string.error),
                                context.getString(R.string.could_not_read_metadata),
                            ),
                        )
                    }
                metadataLoaded = result.isSuccess
                screen.bindDetailsRows(items, ::openMap)
            }
    }

    private fun openMap(action: PhotoMetadataAction.OpenMap) {
        val coordinates = "${action.latitude},${action.longitude}"
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                "geo:$coordinates?q=$coordinates".toUri(),
            )
        // resolveActivity is a package-manager round trip on the main thread; starting and
        // catching the absence costs nothing on the common path.
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Stops the settle animation and any metadata read; call from the Activity's onDestroy. */
    fun release() {
        detailsScrollAnimator?.cancel()
        metadataJob?.cancel()
        metadataJob = null
    }

    private companion object {
        const val DETAILS_FALLBACK_OFFSET_FRACTION = 0.55f
    }
}
