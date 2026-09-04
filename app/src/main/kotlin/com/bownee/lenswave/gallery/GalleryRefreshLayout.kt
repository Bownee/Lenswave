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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) gestureIgnored = !pullGate(ev.y)
        return !gestureIgnored && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) gestureIgnored = !pullGate(ev.y)
        if (ev.actionMasked == MotionEvent.ACTION_UP && gestureIgnored) performClick()
        return !gestureIgnored && super.onTouchEvent(ev)
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
