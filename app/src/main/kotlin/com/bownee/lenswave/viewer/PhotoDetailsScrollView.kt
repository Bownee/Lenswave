package com.bownee.lenswave.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

internal class PhotoDetailsScrollView
    @JvmOverloads
    constructor(
        context: Context,
        attributes: AttributeSet? = null,
    ) : ScrollView(context, attributes) {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }
