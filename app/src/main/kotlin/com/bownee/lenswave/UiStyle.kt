package com.bownee.lenswave

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.AbsListView
import androidx.core.content.ContextCompat

object UiStyle {
    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    val background: Int get() = color(R.color.lenswave_background)
    val surface: Int get() = color(R.color.lenswave_surface)
    val surfaceRaised: Int get() = color(R.color.lenswave_surface_raised)
    val border: Int get() = color(R.color.lenswave_border)
    val text: Int get() = color(R.color.lenswave_text)
    val muted: Int get() = color(R.color.lenswave_muted)
    val accent: Int get() = color(R.color.lenswave_accent)
    val accentDark: Int get() = color(R.color.lenswave_accent_container)

    private fun color(resource: Int): Int = ContextCompat.getColor(applicationContext, resource)

    fun rounded(context: Context, fill: Int, radiusDp: Int, stroke: Int? = border) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(context.dp(1), stroke)
        }
}

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun View.applyVisibleVerticalScrollbar() {
    isVerticalScrollBarEnabled = true
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    setScrollBarSize(context.dp(5))
    setScrollBarDefaultDelayBeforeFade(2_000)
    setScrollBarFadeDuration(450)
    setScrollbarFadingEnabled(true)
    setVerticalScrollbarPosition(
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            View.SCROLLBAR_POSITION_LEFT
        } else {
            View.SCROLLBAR_POSITION_RIGHT
        }
    )
    verticalScrollbarThumbDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(3).toFloat()
        setColor(Color.argb(230, 214, 218, 228))
    }
}

internal fun AbsListView.applyDraggableFastScroll() {
    isVerticalScrollBarEnabled = false
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    setVerticalScrollbarPosition(
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            View.SCROLLBAR_POSITION_LEFT
        } else {
            View.SCROLLBAR_POSITION_RIGHT
        }
    )
    isFastScrollAlwaysVisible = false
    isFastScrollEnabled = false
}
