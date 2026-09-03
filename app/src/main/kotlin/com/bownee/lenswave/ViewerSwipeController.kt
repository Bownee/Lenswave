package com.bownee.lenswave

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Horizontal swiping between neighbouring photos: drags the media aside, slides the neighbour's
 * thumbnail in as a peek, and asks the Activity to commit the navigation once the swipe settles.
 * The Activity remains the owner of `request`, `navigationRequests` and `photoTransitioning`;
 * this controller reads them and requests changes through [Host].
 */
internal class ViewerSwipeController(
    private val screen: PhotoViewerScreen,
    private val mediaTransform: ViewerMediaTransform,
    private val scope: CoroutineScope,
    private val loadThumbnail: suspend (PhotoRequest) -> Bitmap?,
    private val host: Host,
) {
    internal interface Host {
        val request: PhotoRequest
        val navigationRequests: List<PhotoRequest>

        /** True while the details sheet is open, a photo transition runs or the viewer dismisses. */
        val gesturesBlocked: Boolean
        fun activeMediaView(): View

        /** Called before the slide-out animation: raise `photoTransitioning`, remember the fallback. */
        fun beginNavigation()

        /**
         * Called once the current media has slid away: make [adjacent] the current request, reset
         * the media state, adopt the peek (via [adoptPeekAsPreview]) and load the new photo.
         */
        fun commitNavigation(adjacent: PhotoRequest)

        /** Installs [bitmap] as the thumbnail stand-in for the current request. */
        fun adoptPreview(bitmap: Bitmap)
    }

    private val root get() = screen.root
    private val photoView get() = screen.photoView
    private val playerView get() = screen.playerView
    private val thumbnailPreview get() = screen.thumbnailPreview
    private val peekPreview get() = screen.peekPreview
    private val loadingPanel get() = screen.loadingPanel
    private val mediaTitle get() = screen.mediaTitle

    private var peekStableId: String? = null
    private var peekOffset = 0
    private var peekDragDistance = 0f
    private var peekJob: Job? = null

    fun handleHorizontalPhotoDrag(distance: Float, finished: Boolean) {
        if (host.gesturesBlocked) return
        if (distance == 0f) {
            if (finished) resetHorizontalPhotoDrag()
            return
        }

        val offset = if (distance > 0f) 1 else -1
        val adjacent = adjacentTo(host.request.stableId, offset)
        if (adjacent == null) {
            resetHorizontalPhotoDrag()
            return
        }
        if (!finished) {
            mediaTransform.cancelMediaAnimations()
            val boundedDistance = distance.coerceIn(-root.width.toFloat(), root.width.toFloat())
            mediaTransform.setMediaTranslationX(-boundedDistance)
            showPeek(adjacent, offset, boundedDistance)
            return
        }

        val threshold = max(root.width * 0.1f, dp(40).toFloat())
        if (abs(distance) >= threshold) navigatePhoto(offset) else resetHorizontalPhotoDrag()
    }

    private fun resetHorizontalPhotoDrag() {
        mediaTransform.cancelMediaAnimations()
        mediaTransform.animateMediaTranslationX(0f, SWIPE_SETTLE_MILLIS)
        if (peekPreview.isVisible) {
            val settledPeek = peekStableId
            peekPreview.animate()
                .translationX(peekOffset * peekDistance())
                .setDuration(SWIPE_SETTLE_MILLIS)
                // A new drag may have started a different peek before this one settled.
                .withEndAction { if (peekStableId == settledPeek) hidePeek() }
                .start()
        } else {
            hidePeek()
        }
    }

    /** Positions the neighbour's thumbnail one screen away in the drag direction, loading it first. */
    private fun showPeek(adjacent: PhotoRequest, offset: Int, dragDistance: Float) {
        peekDragDistance = dragDistance
        if (peekStableId != adjacent.stableId || peekOffset != offset) {
            peekJob?.cancel()
            peekStableId = adjacent.stableId
            peekOffset = offset
            peekPreview.setImageDrawable(null)
            peekPreview.visibility = View.GONE
            peekJob = scope.launch {
                val bitmap = loadThumbnail(adjacent)
                if (bitmap == null || peekStableId != adjacent.stableId) return@launch
                peekPreview.setImageBitmap(bitmap)
                peekPreview.alpha = 1f
                peekPreview.visibility = View.VISIBLE
                positionPeek(peekDragDistance)
            }
        }
        positionPeek(dragDistance)
    }

    /** How far the neighbour rests from the current photo: one screen plus a small gap. */
    private fun peekDistance(): Float = root.width + dp(PEEK_GAP_DP).toFloat()

    private fun positionPeek(dragDistance: Float) {
        peekPreview.animate().cancel()
        peekPreview.translationX = -dragDistance + peekOffset * peekDistance()
    }

    fun hidePeek() {
        peekJob?.cancel()
        peekJob = null
        peekStableId = null
        peekOffset = 0
        peekPreview.animate().cancel()
        peekPreview.setImageDrawable(null)
        peekPreview.visibility = View.GONE
        peekPreview.translationX = 0f
    }

    /**
     * After a completed swipe the peek is already showing the new photo's thumbnail at rest, so it
     * becomes the preview directly instead of reloading it through a black frame.
     */
    fun adoptPeekAsPreview() {
        val bitmap = (peekPreview.drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null || peekStableId != host.request.stableId) {
            hidePeek()
            return
        }
        host.adoptPreview(bitmap)
        hidePeek()
    }

    fun navigatePhoto(offset: Int) {
        if (host.gesturesBlocked) return
        val adjacent = adjacentTo(host.request.stableId, offset) ?: return

        host.beginNavigation()
        mediaTransform.cancelMediaAnimations()
        if (peekPreview.isVisible && peekStableId == adjacent.stableId) {
            peekPreview.animate().translationX(0f).setDuration(SWIPE_SETTLE_MILLIS).start()
        }
        val activeMedia = host.activeMediaView()
        activeMedia.animate()
            .translationX(-offset * peekDistance())
            .setDuration(SWIPE_SETTLE_MILLIS)
            .withEndAction {
                thumbnailPreview.animate().cancel()
                loadingPanel.animate().cancel()
                host.commitNavigation(adjacent)
            }
            .start()
        if (activeMedia !== photoView) {
            photoView.animate().translationX(-offset * peekDistance()).setDuration(SWIPE_SETTLE_MILLIS).start()
        }
        if (activeMedia !== playerView) {
            playerView.animate().translationX(-offset * peekDistance()).setDuration(SWIPE_SETTLE_MILLIS).start()
        }
        thumbnailPreview.animate()
            .translationX(-offset * peekDistance())
            .setDuration(SWIPE_SETTLE_MILLIS)
            .start()
        loadingPanel.animate()
            .translationX(-offset * peekDistance())
            .setDuration(SWIPE_SETTLE_MILLIS)
            .start()
        mediaTitle.animate()
            .translationX(-offset * peekDistance())
            .setDuration(SWIPE_SETTLE_MILLIS)
            .start()
    }

    fun adjacentTo(stableId: String, offset: Int): PhotoRequest? {
        val requests = host.navigationRequests
        val index = requests.indexOfFirst { it.stableId == stableId }
        return if (index < 0) null else requests.getOrNull(index + offset)
    }

    /** Cancels the pending peek load and clears the peek; call from the Activity's onDestroy. */
    fun release() = hidePeek()

    private fun dp(value: Int): Int = root.context.dp(value)

    private companion object {
        const val PEEK_GAP_DP = 10
        const val SWIPE_SETTLE_MILLIS = 160L
    }
}
