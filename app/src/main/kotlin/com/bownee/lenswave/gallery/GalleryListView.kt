package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ListView

internal class GalleryListView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = android.R.attr.listViewStyle,
) : ListView(context, attributes, defaultStyleAttribute) {
    private val fastScroller = GalleryFastScroller(this, context)

    fun setOnFastScrollInteractionListener(listener: (Boolean) -> Unit) {
        fastScroller.interactionListener = listener
    }

    fun setFastScrollEdgeInset(inset: Int) {
        fastScroller.setEdgeInset(inset)
    }

    /** Switches between the draggable handle and the platform scrollbar. */
    fun setFastScrollHandleEnabled(enabled: Boolean) {
        fastScroller.isEnabled = enabled
        isVerticalScrollBarEnabled = !enabled
    }

    internal fun fastScrollOffset(): Int = computeVerticalScrollOffset()

    internal fun fastScrollRange(): Int = computeVerticalScrollRange()

    internal fun fastScrollExtent(): Int = computeVerticalScrollExtent()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) fastScroller.begin(event)
        if (fastScroller.isDragging) return fastScroller.handle(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        fastScroller.draw(canvas)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        fastScroller.updateVisibility()
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        fastScroller.updateVisibility()
    }

    override fun onDetachedFromWindow() {
        fastScroller.detach()
        super.onDetachedFromWindow()
    }
}
