package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.ListView
import com.bownee.lenswave.R

internal enum class PullRefreshPhase {
    IDLE,
    PULLING,
    READY,
    REFRESHING,
}

internal data class PullRefreshIndicatorState(
    val phase: PullRefreshPhase,
    val progress: Float,
)

internal class PullToRefreshListView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = android.R.attr.listViewStyle,
) : ListView(context, attributes, defaultStyleAttribute) {
    private val refreshThreshold = resources.getDimensionPixelSize(R.dimen.gallery_refresh_threshold).toFloat()
    private val fastScroller = GalleryFastScroller(this, context)
    private var startedAtTop = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var downwardDistance = 0f
    private var refreshing = false
    private var indicatorPhase = PullRefreshPhase.IDLE
    private var refreshListener: (() -> Unit)? = null
    private var indicatorListener: ((PullRefreshIndicatorState) -> Unit)? = null

    fun setOnPullRefreshListener(listener: () -> Unit) {
        refreshListener = listener
    }

    fun setOnPullRefreshIndicatorListener(listener: (PullRefreshIndicatorState) -> Unit) {
        indicatorListener = listener
    }

    fun setOnFastScrollInteractionListener(listener: (Boolean) -> Unit) {
        fastScroller.interactionListener = listener
    }

    fun setFastScrollEdgeInset(inset: Int) {
        fastScroller.setEdgeInset(inset)
    }

    fun setRefreshing(refreshing: Boolean) {
        if (this.refreshing == refreshing) return
        this.refreshing = refreshing
        showIndicator(
            phase = if (refreshing) PullRefreshPhase.REFRESHING else PullRefreshPhase.IDLE,
            progress = if (refreshing) 1f else 0f,
        )
    }

    internal fun fastScrollOffset(): Int = computeVerticalScrollOffset()

    internal fun fastScrollRange(): Int = computeVerticalScrollRange()

    internal fun fastScrollExtent(): Int = computeVerticalScrollExtent()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
        if (fastScroller.isDragging) return fastScroller.handle(event)

        var shouldRefresh = false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateGesture(event)
            MotionEvent.ACTION_UP -> {
                downwardDistance = (event.rawY - startRawY).coerceAtLeast(0f)
                shouldRefresh = !refreshing && refreshListener != null && PullRefreshPolicy.shouldRefresh(
                    startedAtTop = startedAtTop,
                    startedInFastScrollRegion = fastScroller.isDragging,
                    horizontalDistance = event.rawX - startRawX,
                    downwardDistance = downwardDistance,
                    threshold = refreshThreshold,
                )
                resetGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                resetGesture()
                if (!refreshing) showIndicator(PullRefreshPhase.IDLE, 0f)
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (shouldRefresh) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                setRefreshing(true)
                post { refreshListener?.invoke() }
            } else if (!refreshing) {
                showIndicator(PullRefreshPhase.IDLE, 0f)
            }
        }
        return handled
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        fastScroller.draw(canvas)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        updateFastScrollVisibility()
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateFastScrollVisibility()
    }

    private fun updateFastScrollVisibility() {
        fastScroller.updateVisibility()
    }

    private fun beginGesture(event: MotionEvent) {
        fastScroller.begin(event)
        startedAtTop = !canScrollVertically(-1)
        startRawX = event.rawX
        startRawY = event.rawY
        downwardDistance = 0f
    }

    private fun updateGesture(event: MotionEvent) {
        if (refreshing) return
        val horizontalDistance = event.rawX - startRawX
        downwardDistance = (event.rawY - startRawY).coerceAtLeast(0f)
        if (!PullRefreshPolicy.isPullGesture(
                startedAtTop,
                fastScroller.isDragging,
                horizontalDistance,
                downwardDistance,
            )
        ) {
            showIndicator(PullRefreshPhase.IDLE, 0f)
            return
        }

        val progress = PullRefreshPolicy.progress(downwardDistance, refreshThreshold)
        showIndicator(
            phase = if (progress >= 1f) PullRefreshPhase.READY else PullRefreshPhase.PULLING,
            progress = progress,
        )
    }

    private fun showIndicator(phase: PullRefreshPhase, progress: Float) {
        if (phase == indicatorPhase && (phase == PullRefreshPhase.IDLE || phase == PullRefreshPhase.REFRESHING)) {
            return
        }
        indicatorPhase = phase
        indicatorListener?.invoke(PullRefreshIndicatorState(phase, progress))
    }

    private fun resetGesture() {
        startedAtTop = false
        downwardDistance = 0f
    }
}
