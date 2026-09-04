package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ListView

internal class GalleryListView
    @JvmOverloads
    constructor(
        context: Context,
        attributes: AttributeSet? = null,
        defaultStyleAttribute: Int = android.R.attr.listViewStyle,
    ) : ListView(context, attributes, defaultStyleAttribute) {
        private val fastScroller = GalleryFastScroller(this, context)

        init {
            // The platform fast scroller is replaced by the drag handle; the plain scrollbar stays
            // available for pages where the handle is disabled.
            isVerticalScrollBarEnabled = false
            scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
            setVerticalScrollbarPosition(
                if (layoutDirection == LAYOUT_DIRECTION_RTL) SCROLLBAR_POSITION_LEFT else SCROLLBAR_POSITION_RIGHT,
            )
            isFastScrollAlwaysVisible = false
            isFastScrollEnabled = false
        }

        fun setOnFastScrollInteractionListener(listener: (Boolean) -> Unit) {
            fastScroller.interactionListener = listener
        }

        fun setFastScrollEdgeInsets(
            top: Int,
            bottom: Int,
        ) {
            fastScroller.setEdgeInsets(top, bottom)
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
            // A handle drag must not be mistaken for a pull-to-refresh by the parent.
            if (fastScroller.isDragging) parent?.requestDisallowInterceptTouchEvent(true)
            if (fastScroller.isDragging) return fastScroller.handle(event)
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            fastScroller.draw(canvas)
        }

        override fun onScrollChanged(
            left: Int,
            top: Int,
            oldLeft: Int,
            oldTop: Int,
        ) {
            super.onScrollChanged(left, top, oldLeft, oldTop)
            // The list redraws itself as it scrolls; only the handle's own strip needs marking.
            fastScroller.onListScrolled()
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.onLayout(changed, left, top, right, bottom)
            fastScroller.updateVisibility()
        }

        override fun onDetachedFromWindow() {
            fastScroller.detach()
            super.onDetachedFromWindow()
        }
    }
