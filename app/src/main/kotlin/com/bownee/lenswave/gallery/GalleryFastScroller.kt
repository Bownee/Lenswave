package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isEmpty
import androidx.core.view.ViewCompat
import com.bownee.lenswave.R
import com.bownee.lenswave.dp
import kotlin.math.roundToInt

internal class GalleryFastScroller(
    private val listView: PullToRefreshListView,
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
    private var edgeInset = 0
    private var pointerOffset = 0f

    var isDragging = false
        private set

    var isVisible = false
        private set

    var interactionListener: ((Boolean) -> Unit)? = null

    fun setEdgeInset(inset: Int) {
        edgeInset = inset.coerceAtLeast(0)
        listView.invalidate()
    }

    fun begin(event: MotionEvent): Boolean {
        updateHandleBounds()
        if (!isVisible || !isInsideTouchTarget(event)) return false
        isDragging = true
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

    fun updateVisibility() {
        val shouldShow = GalleryFastScrollLayoutPolicy.shouldShow(
            canScrollBackward = listView.canScrollVertically(-1),
            canScrollForward = listView.canScrollVertically(1),
        )
        if (isVisible == shouldShow) return
        isVisible = shouldShow
        if (!shouldShow) end()
        listView.invalidate()
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
        listView.invalidate()
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
        val gestureWidth = listView.context.dp(GESTURE_WIDTH_DP)
        val verticalExpansion = listView.context.dp(TOUCH_EXPANSION_DP)
        val insideHorizontal = if (isRtl()) event.x <= gestureWidth else event.x >= listView.width - gestureWidth
        return insideHorizontal &&
            event.y >= handleBounds.top - verticalExpansion &&
            event.y <= handleBounds.bottom + verticalExpansion
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

    private fun trackStart(): Int = edgeInset

    private fun trackEnd(): Int = (listView.height - edgeInset).coerceAtLeast(trackStart())

    private fun isRtl(): Boolean =
        listView.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private companion object {
        const val GESTURE_WIDTH_DP = 48
        const val EDGE_MARGIN_DP = 4
        const val TOUCH_EXPANSION_DP = 8
    }
}
