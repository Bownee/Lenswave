package com.bownee.lenswave

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.ImageButton
import androidx.annotation.DrawableRes
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
    val accentSoft: Int get() = color(R.color.lenswave_accent_soft)
    val accentDark: Int get() = color(R.color.lenswave_accent_container)
    val onAccent: Int get() = color(R.color.lenswave_on_accent)
    val danger: Int get() = color(R.color.lenswave_error)
    val dangerSoft: Int get() = color(R.color.lenswave_error_soft)

    val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun color(resource: Int): Int = ContextCompat.getColor(applicationContext, resource)

    internal fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(context: Context, fill: Int, radiusDp: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(context.dp(1), stroke)
        }

    fun circle(context: Context, fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(context.dp(1), stroke)
    }

    /** Wraps [background] so taps show a soft ripple clipped to the background's shape. */
    fun rippled(background: Drawable, tint: Int = text): Drawable =
        RippleDrawable(ColorStateList.valueOf(withAlpha(tint, 46)), background, background)

    fun iconButton(
        context: Context,
        @DrawableRes icon: Int,
        description: String,
        fill: Int = surfaceRaised,
        tint: Int = text,
        sizeDp: Int = 44,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        val inset = context.dp((sizeDp - 22) / 2)
        setPadding(inset, inset, inset, inset)
        background = rippled(circle(context, fill), tint)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }

    fun clipRounded(view: View, radiusDp: Int) {
        val radius = view.context.dp(radiusDp).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radius)
            }
        }
        view.clipToOutline = true
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
