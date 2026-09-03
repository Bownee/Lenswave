package com.bownee.lenswave.viewer

import com.bownee.lenswave.R
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import com.bownee.lenswave.metadata.PhotoMetadataAction
import com.bownee.lenswave.metadata.PhotoMetadataItem
import com.bownee.lenswave.metadata.PhotoMetadataReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        fun handlePhotoDismissDrag(distance: Float, velocity: Float, finished: Boolean)
        fun resetPhotoDismiss()
    }

    private val root get() = screen.root
    private val photoDetailsScroll get() = screen.photoDetailsScroll
    private val mediaFrame get() = screen.mediaFrame
    private val mediaTitle get() = screen.mediaTitle
    private val actions get() = screen.actions
    private val detailsSheet get() = screen.detailsSheet
    private val detailsContent get() = screen.detailsContent
    private val detailsProgress get() = screen.detailsProgress
    private val resources get() = context.resources

    var shown = false
        private set
    private var metadataLoaded = false
    private var detailsScrollAnimator: ValueAnimator? = null
    private var detailsDragStartOffset: Int? = null
    private var detailsDragStartedShown = false
    private var detailsSheetAttachmentOffset = 0

    fun handleDetailsDrag(distance: Float, velocity: Float, finished: Boolean) {
        if (host.gesturesBlocked) return
        if (!shown && detailsDragStartOffset == null && distance < 0f) {
            host.handlePhotoDismissDrag(-distance, -velocity, finished)
            return
        }

        if (!finished) {
            val startOffset = detailsDragStartOffset ?: photoDetailsScroll.scrollY.also {
                detailsDragStartOffset = it
                detailsDragStartedShown = shown
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
        val targetOffset = VerticalGesturePolicy.detailsSettleOffset(
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

    private fun setDetailsOffset(offset: Int) {
        val boundedOffset = offset.coerceIn(0, maximumDetailsOffset())
        photoDetailsScroll.scrollTo(0, boundedOffset)
        val initialOffset = initialDetailsOffset().coerceAtLeast(1)
        val sheetProgress = (boundedOffset.toFloat() / initialOffset).coerceIn(0f, 1f)
        detailsSheet.alpha = sheetProgress
        detailsSheet.visibility = if (sheetProgress > 0f || shown) View.VISIBLE else View.INVISIBLE
        if (sheetProgress < 1f) actions.visibility = View.VISIBLE
        actions.alpha = 1f - sheetProgress
        mediaTitle.alpha = 1f - sheetProgress
        if (sheetProgress >= 1f && shown) actions.visibility = View.INVISIBLE
    }

    private fun animateDetailsOffset(targetOffset: Int, velocity: Float) {
        detailsScrollAnimator?.cancel()
        val boundedTarget = targetOffset.coerceIn(0, maximumDetailsOffset())
        val startOffset = photoDetailsScroll.scrollY
        val duration = ViewerVerticalSettle.duration(
            (boundedTarget - startOffset).toFloat(),
            velocity,
            resources.displayMetrics.density,
        )
        detailsScrollAnimator = ValueAnimator.ofInt(startOffset, boundedTarget).apply {
            this.duration = duration
            interpolator = ViewerVerticalSettle.interpolator
            addUpdateListener { animator -> setDetailsOffset(animator.animatedValue as Int) }
            start()
        }
    }

    private fun initialDetailsOffset(): Int = PhotoDetailsLayoutPolicy.initialOffset(
        mediaHeight = mediaFrame.height,
        fittedImageBottom = host.fittedMediaBottom(),
        overlap = resources.getDimensionPixelSize(R.dimen.photo_details_sheet_overlap),
        fallbackOffset = (root.height * DETAILS_FALLBACK_OFFSET_FRACTION).roundToInt(),
        maximumOffset = maximumDetailsOffset(),
    )

    private fun updateDetailsSheetAttachment(): Int {
        val previousOffset = detailsSheetAttachmentOffset
        detailsSheetAttachmentOffset = PhotoDetailsLayoutPolicy.attachmentOffset(
            mediaHeight = mediaFrame.height,
            fittedImageBottom = host.fittedMediaBottom(),
            overlap = resources.getDimensionPixelSize(R.dimen.photo_details_sheet_overlap),
        )
        detailsSheet.translationY = -detailsSheetAttachmentOffset.toFloat()
        return detailsSheetAttachmentOffset - previousOffset
    }

    /** Re-attaches the sheet to the fitted image and keeps the current scroll position sensible. */
    fun synchronizeWithImage() {
        val attachmentChange = updateDetailsSheetAttachment()
        val adjustedOffset = photoDetailsScroll.scrollY - attachmentChange
        val target = if (shown) {
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

    /** Clears the rows and the attachment for the next photo; `shown` is deliberately kept. */
    fun resetForNavigation() {
        metadataLoaded = false
        detailsContent.removeAllViews()
        detailsProgress.visibility = View.VISIBLE
        detailsSheetAttachmentOffset = 0
        detailsSheet.translationY = 0f
        detailsSheet.alpha = 0f
        detailsSheet.visibility = View.INVISIBLE
    }

    fun ensureMetadataLoaded() {
        if (metadataLoaded) return
        val uri = host.resolvedUri ?: return
        loadMetadata(uri)
    }

    private fun loadMetadata(uri: Uri) {
        if (metadataLoaded) return
        metadataLoaded = true
        val metadataRequest = host.request
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    metadataReader.read(
                        context,
                        uri,
                        metadataRequest.displayName,
                        metadataRequest.capturedAt,
                    )
                }
            }
            if (host.request.stableId != metadataRequest.stableId) return@launch
            detailsProgress.visibility = View.GONE
            result.onSuccess { items -> items.forEach(::addDetailsRow) }
                .onFailure {
                    addDetailsRow(
                        PhotoMetadataItem(
                            context.getString(R.string.error),
                            context.getString(R.string.could_not_read_metadata),
                        ),
                    )
                }
        }
    }

    private fun addDetailsRow(item: PhotoMetadataItem) {
        screen.addDetailsRow(item, ::openMap)
    }

    private fun openMap(action: PhotoMetadataAction.OpenMap) {
        val coordinates = "${action.latitude},${action.longitude}"
        val intent = Intent(
            Intent.ACTION_VIEW,
            "geo:$coordinates?q=$coordinates".toUri(),
        )
        if (intent.resolveActivity(context.packageManager) == null) {
            Toast.makeText(context, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
            return
        }
        context.startActivity(intent)
    }

    /** Stops the settle animation; call from the Activity's onDestroy. */
    fun release() {
        detailsScrollAnimator?.cancel()
    }

    private companion object {
        const val DETAILS_FALLBACK_OFFSET_FRACTION = 0.55f
    }
}
