package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
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
    private val defaultDrawable = requireNotNull(
        ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.gallery_fast_scroll_thumb_default,
            context.theme,
        ),
    ).mutate()
    private val pressedDrawable = requireNotNull(
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
    private val hideHandle = Runnable {
        if (isDragging) return@Runnable
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
    fun setEdgeInsets(top: Int, bottom: Int) {
        topInset = top.coerceAtLeast(0)
        bottomInset = bottom.coerceAtLeast(0)
        listView.invalidate()
    }

    fun begin(event: MotionEvent): Boolean {
        updateHandleBounds()
        if (!isVisible || !isInsideTouchTarget(event)) return false
        isDragging = true
        listView.removeCallbacks(hideHandle)
        pointerOffset = (event.y - handleBounds.top).coerceIn(0f, handleSize.toFloat())
        interactionListener?.invoke(true)
        listView.invalidate()
        return true
    }

    fun handle(event: MotionEvent): Boolean {
        if (!isDragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> scrollFromPointer(event.y)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            end()
        }
        return true
    }

    /** Shows the handle while the list can scroll and schedules it to fade once scrolling settles. */
    fun updateVisibility() {
        val shouldShow = isEnabled && GalleryFastScrollLayoutPolicy.shouldShow(
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

    fun detach() {
        listView.removeCallbacks(hideHandle)
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
        listView.removeCallbacks(hideHandle)
        if (isDragging) end()
        if (!isVisible) return
        isVisible = false
        listView.invalidate()
    }

    private fun scheduleHide() {
        listView.removeCallbacks(hideHandle)
        listView.postDelayed(hideHandle, HIDE_DELAY_MILLIS)
    }

    private fun scrollFromPointer(pointerY: Float) {
        val progress = GalleryFastScrollLayoutPolicy.dragProgress(
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

    private fun isInsideTouchTarget(event: MotionEvent): Boolean {
        val expansion = listView.context.dp(TOUCH_EXPANSION_DP)
        return event.x >= handleBounds.left - expansion &&
            event.x <= handleBounds.right + expansion &&
            event.y >= handleBounds.top - expansion &&
            event.y <= handleBounds.bottom + expansion
    }

    private fun updateHandleBounds() {
        val top = GalleryFastScrollLayoutPolicy.handleTop(
            scrollOffset = listView.fastScrollOffset(),
            scrollRange = listView.fastScrollRange(),
            viewportExtent = listView.fastScrollExtent(),
            trackStart = trackStart(),
            trackEnd = trackEnd(),
            handleSize = handleSize,
        )
        val margin = listView.context.dp(EDGE_MARGIN_DP)
        if (isRtl()) {
            handleBounds.set(margin, top, margin + handleSize, top + handleSize)
        } else {
            val right = listView.width - margin
            handleBounds.set(right - handleSize, top, right, top + handleSize)
        }
    }

    private fun trackStart(): Int = topInset

    private fun trackEnd(): Int = (listView.height - bottomInset).coerceAtLeast(trackStart())

    private fun isRtl(): Boolean =
        listView.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private companion object {
        const val EDGE_MARGIN_DP = 4
        const val TOUCH_EXPANSION_DP = 10
        const val HIDE_DELAY_MILLIS = 1_400L
    }
}
