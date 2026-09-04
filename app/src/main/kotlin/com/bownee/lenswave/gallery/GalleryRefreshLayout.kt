package com.bownee.lenswave.gallery

import android.content.Context
import android.view.MotionEvent
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

    // Lint requires the override to accompany onTouchEvent; the layout has no click of its own.
    override fun performClick(): Boolean = super.performClick()
}
