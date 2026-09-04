package com.bownee.lenswave.gallery

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * A [SwipeRefreshLayout] that only reacts to gestures starting where [pullGate] allows, so the
 * pinned header and the filter chips keep their own touch handling.
 */
internal class GalleryRefreshLayout(
    context: Context,
) : SwipeRefreshLayout(context) {
    /** Receives the touch-down y position; a false result ignores the whole gesture. */
    var pullGate: (touchY: Float) -> Boolean = { true }
    private var gestureIgnored = false

    // The same ACTION_DOWN reaches onInterceptTouchEvent and then onTouchEvent; the gate is asked once per gesture.
    private var gatedDownTime = Long.MIN_VALUE

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gateGesture(ev)
        return !gestureIgnored && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gateGesture(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && gestureIgnored) performClick()
        return !gestureIgnored && super.onTouchEvent(ev)
    }

    private fun gateGesture(ev: MotionEvent) {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN || ev.downTime == gatedDownTime) return
        gatedDownTime = ev.downTime
        gestureIgnored = !pullGate(ev.y)
    }

    /**
     * The list drives the spinner through nested scrolling rather than through intercepted
     * touches, so an ignored gesture must also be refused here or a drag that starts on the
     * chips still pulls once the list reports its over-scroll.
     */
    override fun onStartNestedScroll(
        child: View,
        target: View,
        nestedScrollAxes: Int,
    ): Boolean = !gestureIgnored && super.onStartNestedScroll(child, target, nestedScrollAxes)

    // Lint requires the override to accompany onTouchEvent; the layout has no click of its own.
    override fun performClick(): Boolean = super.performClick()
}
