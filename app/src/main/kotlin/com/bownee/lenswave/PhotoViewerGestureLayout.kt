package com.bownee.lenswave

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

internal class PhotoViewerGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var gesturesEnabled: () -> Boolean = { true }
    var gestureStartAllowed: (x: Float, y: Float) -> Boolean = { _, _ -> true }
    var onVerticalDrag: ((distance: Float, velocity: Float, finished: Boolean) -> Unit)? = null
    var onHorizontalDrag: ((distance: Float, finished: Boolean) -> Unit)? = null

    private val directionalDragSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.35f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDistanceX = 0f
    private var dragDistanceY = 0f
    private var dragVelocityY = 0f
    private var lastRawY = 0f
    private var lastEventTime = 0L
    private var dragAxis = DragAxis.NONE
    private var dragBlocked = false
    private var interceptingDrag = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginDrag(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragBlocked = true
                dragAxis = DragAxis.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (!canTrack(event)) return false
                updateDrag(event)
                lockDragAxis()
                if (dragAxis != DragAxis.NONE) {
                    interceptingDrag = true
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> resetDrag()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interceptingDrag) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (canTrack(event)) {
                    updateDrag(event)
                    dispatchDrag(finished = false)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (canTrack(event)) updateDrag(event)
                if (max(abs(dragDistanceX), abs(dragDistanceY)) < directionalDragSlop) performClick()
                dispatchDrag(finished = true)
                resetDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                resetDrag()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginDrag(event: MotionEvent) {
        dragStartX = event.rawX
        dragStartY = event.rawY
        dragDistanceX = 0f
        dragDistanceY = 0f
        dragVelocityY = 0f
        lastRawY = event.rawY
        lastEventTime = event.eventTime
        dragAxis = DragAxis.NONE
        dragBlocked = !gestureStartAllowed(event.x, event.y)
        interceptingDrag = false
    }

    private fun canTrack(event: MotionEvent): Boolean =
        event.pointerCount == 1 && !dragBlocked && gesturesEnabled()

    private fun updateDrag(event: MotionEvent) {
        val elapsedMillis = event.eventTime - lastEventTime
        if (elapsedMillis > 0L) {
            val instantaneousVelocity = (lastRawY - event.rawY) * 1_000f / elapsedMillis
            dragVelocityY = if (dragVelocityY == 0f || elapsedMillis > MAX_VELOCITY_SAMPLE_GAP_MILLIS) {
                instantaneousVelocity
            } else {
                dragVelocityY * VELOCITY_HISTORY_WEIGHT +
                    instantaneousVelocity * (1f - VELOCITY_HISTORY_WEIGHT)
            }
            lastRawY = event.rawY
            lastEventTime = event.eventTime
        }
        dragDistanceX = dragStartX - event.rawX
        dragDistanceY = dragStartY - event.rawY
    }

    private fun lockDragAxis() {
        if (dragAxis != DragAxis.NONE) return
        val horizontalDistance = abs(dragDistanceX)
        val verticalDistance = abs(dragDistanceY)
        if (max(horizontalDistance, verticalDistance) < directionalDragSlop) return
        dragAxis = when {
            verticalDistance > horizontalDistance * AXIS_DOMINANCE -> DragAxis.VERTICAL
            horizontalDistance > verticalDistance * AXIS_DOMINANCE -> DragAxis.HORIZONTAL
            else -> DragAxis.NONE
        }
    }

    private fun dispatchDrag(finished: Boolean) {
        when (dragAxis) {
            DragAxis.VERTICAL -> onVerticalDrag?.invoke(dragDistanceY, dragVelocityY, finished)
            DragAxis.HORIZONTAL -> onHorizontalDrag?.invoke(dragDistanceX, finished)
            DragAxis.NONE -> Unit
        }
    }

    private fun cancelDrag() {
        when (dragAxis) {
            DragAxis.VERTICAL -> onVerticalDrag?.invoke(0f, 0f, true)
            DragAxis.HORIZONTAL -> onHorizontalDrag?.invoke(0f, true)
            DragAxis.NONE -> Unit
        }
    }

    private fun resetDrag() {
        dragDistanceX = 0f
        dragDistanceY = 0f
        dragVelocityY = 0f
        dragAxis = DragAxis.NONE
        dragBlocked = false
        interceptingDrag = false
    }

    private enum class DragAxis {
        NONE,
        HORIZONTAL,
        VERTICAL,
    }

    private companion object {
        const val AXIS_DOMINANCE = 1.15f
        const val VELOCITY_HISTORY_WEIGHT = 0.55f
        const val MAX_VELOCITY_SAMPLE_GAP_MILLIS = 80L
    }
}
