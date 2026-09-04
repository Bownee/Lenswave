package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isEmpty
import com.bownee.lenswave.R
import com.bownee.lenswave.dp
import kotlin.math.roundToInt

/**
 * A draggable handle for jumping through long photo lists.
 *
 * Like the platform scrollbar it only appears while the list is moving and fades out shortly
 * after, and it only grabs touches that land on the handle itself, so ordinary swipes near the
 * edge scroll the list normally.
 */
internal class GalleryFastScroller(
    private val listView: GalleryListView,
    context: Context,
) {
    private val handleSize = context.resources.getDimensionPixelSize(R.dimen.gallery_fast_scroll_handle_size)

    // Resolved once: both are read on every draw and touch, where a density lookup would be waste.
    private val edgeMargin = context.dp(EDGE_MARGIN_DP)
    private val touchExpansion = context.dp(TOUCH_EXPANSION_DP)
    private val defaultDrawable =
        requireNotNull(
            ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.gallery_fast_scroll_thumb_default,
                context.theme,
            ),
        ).mutate()
    private val pressedDrawable =
        requireNotNull(
            ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.gallery_fast_scroll_thumb_pressed,
                context.theme,
            ),
        ).mutate()
    private val handleBounds = Rect()
    private var topInset = 0
    private var bottomInset = 0
    private var pointerOffset = 0f
    private var lastScrollUptimeMillis = 0L
    private var hidePosted = false

    // One pending callback re-checks the last scroll time, so scrolling only stamps a timestamp
    // instead of removing and re-posting a callback on every scroll event.
    private val hideHandle =
        Runnable {
            hidePosted = false
            if (isDragging) return@Runnable
            val remaining =
                GalleryFastScrollLayoutPolicy.remainingHideDelay(
                    lastScrollAt = lastScrollUptimeMillis,
                    now = SystemClock.uptimeMillis(),
                    hideDelay = HIDE_DELAY_MILLIS,
                )
            if (remaining > 0) {
                postHide(remaining)
                return@Runnable
            }
            isVisible = false
            listView.invalidate()
        }

    /** Whether the handle is offered at all; when false the list falls back to the platform scrollbar. */
    var isEnabled = true
        set(value) {
            if (field == value) return
            field = value
            if (value) updateVisibility() else hideNow()
        }

    var isDragging = false
        private set

    var isVisible = false
        private set

    var interactionListener: ((Boolean) -> Unit)? = null

    /** The track runs from [top] below the list's top edge to [bottom] above its bottom edge. */
    fun setEdgeInsets(
        top: Int,
        bottom: Int,
    ) {
        topInset = top.coerceAtLeast(0)
        bottomInset = bottom.coerceAtLeast(0)
        listView.invalidate()
    }

    fun begin(event: MotionEvent): Boolean {
        updateHandleBounds()
        if (!isVisible || !isInsideTouchTarget(event)) return false
        isDragging = true
        cancelHide()
        pointerOffset = (event.y - handleBounds.top).coerceIn(0f, handleSize.toFloat())
        interactionListener?.invoke(true)
        listView.invalidate()
        return true
    }

    fun handle(event: MotionEvent): Boolean {
        if (!isDragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            -> scrollFromPointer(event.y)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            end()
        }
        return true
    }

    /** Shows the handle while the list can scroll and schedules it to fade once scrolling settles. */
    fun updateVisibility() {
        val shouldShow =
            isEnabled &&
                GalleryFastScrollLayoutPolicy.shouldShow(
                    canScrollBackward = listView.canScrollVertically(-1),
                    canScrollForward = listView.canScrollVertically(1),
                )
        if (!shouldShow) {
            hideNow()
            return
        }
        if (!isVisible) {
            isVisible = true
            listView.invalidate()
        }
        scheduleHide()
    }

    /**
     * Follows a list scroll. The list repaints itself for every scroll step and draws the handle
     * in that pass, so this only invalidates when the handle appears or disappears (through
     * [updateVisibility]); a handle that merely moves costs no extra invalidation. (Partial
     * invalidation is a no-op on hardware-accelerated views, so a handle-rect invalidate would
     * not be cheaper than none.)
     */
    fun onListScrolled() {
        updateVisibility()
    }

    fun detach() {
        cancelHide()
    }

    fun draw(canvas: Canvas) {
        if (!isVisible) return
        updateHandleBounds()
        val drawable = if (isDragging) pressedDrawable else defaultDrawable
        drawable.bounds = handleBounds
        drawable.draw(canvas)
    }

    private fun end() {
        if (isDragging) interactionListener?.invoke(false)
        isDragging = false
        pointerOffset = 0f
        scheduleHide()
        listView.invalidate()
    }

    private fun hideNow() {
        cancelHide()
        if (isDragging) end()
        if (!isVisible) return
        isVisible = false
        listView.invalidate()
    }

    private fun scheduleHide() {
        lastScrollUptimeMillis = SystemClock.uptimeMillis()
        if (!hidePosted) postHide(HIDE_DELAY_MILLIS)
    }

    private fun postHide(delayMillis: Long) {
        hidePosted = true
        listView.postDelayed(hideHandle, delayMillis)
    }

    private fun cancelHide() {
        listView.removeCallbacks(hideHandle)
        hidePosted = false
    }

    private fun scrollFromPointer(pointerY: Float) {
        val progress =
            GalleryFastScrollLayoutPolicy.dragProgress(
                pointerY = pointerY,
                pointerOffsetInHandle = pointerOffset,
                trackStart = trackStart(),
                trackEnd = trackEnd(),
                handleSize = handleSize,
            )
        val target = GalleryFastScrollLayoutPolicy.target(listView.count, progress)
        val rowOffset = (averageVisibleRowHeight() * target.positionOffsetFraction).roundToInt()
        listView.setSelectionFromTop(target.position, -rowOffset)
        listView.invalidate()
    }

    private fun averageVisibleRowHeight(): Int {
        if (listView.isEmpty()) return 1
        var combinedHeight = 0
        for (index in 0 until listView.childCount) combinedHeight += listView.getChildAt(index).height
        return (combinedHeight / listView.childCount).coerceAtLeast(1)
    }

    private fun isInsideTouchTarget(event: MotionEvent): Boolean =
        event.x >= handleBounds.left - touchExpansion &&
            event.x <= handleBounds.right + touchExpansion &&
            event.y >= handleBounds.top - touchExpansion &&
            event.y <= handleBounds.bottom + touchExpansion

    private fun updateHandleBounds() {
        val top =
            GalleryFastScrollLayoutPolicy.handleTop(
                scrollOffset = listView.fastScrollOffset(),
                scrollRange = listView.fastScrollRange(),
                viewportExtent = listView.fastScrollExtent(),
                trackStart = trackStart(),
                trackEnd = trackEnd(),
                handleSize = handleSize,
            )
        if (isRtl()) {
            handleBounds.set(edgeMargin, top, edgeMargin + handleSize, top + handleSize)
        } else {
            val right = listView.width - edgeMargin
            handleBounds.set(right - handleSize, top, right, top + handleSize)
        }
    }

    private fun trackStart(): Int = topInset

    private fun trackEnd(): Int = (listView.height - bottomInset).coerceAtLeast(trackStart())

    private fun isRtl(): Boolean = listView.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private companion object {
        const val EDGE_MARGIN_DP = 4
        const val TOUCH_EXPANSION_DP = 10
        const val HIDE_DELAY_MILLIS = 1_400L
    }
}
