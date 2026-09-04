package com.bownee.lenswave

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.TextViewCompat

/** Colours, typography and the small set of view factories every screen is built from. */
object UiStyle {
    // The palette has no configuration qualifiers, so every colour, the typeface and the two
    // state strings are resolved once in initialize() instead of on each property read or bind.
    var background: Int = 0
        private set
    var surface: Int = 0
        private set
    var surfaceRaised: Int = 0
        private set
    var border: Int = 0
        private set
    var text: Int = 0
        private set
    var muted: Int = 0
        private set
    var accent: Int = 0
        private set
    var accentSoft: Int = 0
        private set
    var onAccent: Int = 0
        private set
    var danger: Int = 0
        private set
    var dangerSoft: Int = 0
        private set

    lateinit var medium: Typeface
        private set

    private lateinit var selectedDescription: String
    private lateinit var notSelectedDescription: String

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext

        fun color(resource: Int): Int = ContextCompat.getColor(applicationContext, resource)
        background = color(R.color.lenswave_background)
        surface = color(R.color.lenswave_surface)
        surfaceRaised = color(R.color.lenswave_surface_raised)
        border = color(R.color.lenswave_border)
        text = color(R.color.lenswave_text)
        muted = color(R.color.lenswave_muted)
        accent = color(R.color.lenswave_accent)
        accentSoft = color(R.color.lenswave_accent_soft)
        onAccent = color(R.color.lenswave_on_accent)
        danger = color(R.color.lenswave_error)
        dangerSoft = color(R.color.lenswave_error_soft)
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        selectedDescription = applicationContext.getString(R.string.selected)
        notSelectedDescription = applicationContext.getString(R.string.not_selected)
    }

    internal fun withAlpha(
        color: Int,
        alpha: Int,
    ): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Int,
        stroke: Int? = null,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(context.dp(1), stroke)
    }

    fun circle(
        context: Context,
        fill: Int,
        stroke: Int? = null,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(context.dp(1), stroke)
    }

    /** Wraps [background] so taps show a soft ripple clipped to the background's shape. */
    fun rippled(
        background: Drawable,
        tint: Int = text,
    ): Drawable = RippleDrawable(ColorStateList.valueOf(withAlpha(tint, 46)), background, background)

    fun label(
        context: Context,
        text: CharSequence = "",
        sizeSp: Float,
        color: Int = this.text,
        medium: Boolean = false,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (medium) typeface = this@UiStyle.medium
        }

    fun iconButton(
        context: Context,
        @DrawableRes icon: Int,
        description: String,
        fill: Int = surfaceRaised,
        tint: Int = text,
        sizeDp: Int = 44,
        iconDp: Int = 22,
    ): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = description
            val inset = context.dp((sizeDp - iconDp) / 2)
            setPadding(inset, inset, inset, inset)
            background = rippled(circle(context, fill), tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    /** A filled accent call-to-action button. */
    fun accentButton(
        context: Context,
        label: String,
        radiusDp: Int = 25,
    ): Button =
        Button(context).apply {
            text = label
            textSize = 15f
            typeface = medium
            setTextColor(onAccent)
            isAllCaps = false
            background = rippled(rounded(context, accent, radiusDp), onAccent)
        }

    /** A compact pill with a leading icon; destructive pills use the danger palette. */
    fun pillButton(
        context: Context,
        label: String,
        @DrawableRes icon: Int,
        destructive: Boolean = false,
    ): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            typeface = medium
            val tint = if (destructive) danger else this@UiStyle.text
            setTextColor(tint)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(tint))
            compoundDrawablePadding = context.dp(6)
            setPadding(context.dp(14), 0, context.dp(16), 0)
            background = rippled(rounded(context, if (destructive) dangerSoft else surfaceRaised, 22), tint)
        }

    /** Shows the filled or outlined heart and the matching accessibility label. */
    fun applyFavoriteIcon(
        view: ImageView,
        favorite: Boolean,
    ) {
        view.setImageResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        view.contentDescription =
            view.context.getString(
                if (favorite) R.string.remove_from_favorites else R.string.add_to_favorites,
            )
    }

    /** Mirrors [selected] into the view's state and its spoken state description. */
    fun setSelectedState(
        view: View,
        selected: Boolean,
    ) {
        view.isSelected = selected
        ViewCompat.setStateDescription(view, if (selected) selectedDescription else notSelectedDescription)
    }

    fun clipRounded(
        view: View,
        radiusDp: Int,
    ) {
        val radius = view.context.dp(radiusDp).toFloat()
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    target: View,
                    outline: Outline,
                ) {
                    outline.setRoundRect(0, 0, target.width, target.height, radius)
                }
            }
        view.clipToOutline = true
    }

    fun matchWrap() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    fun matchParentFrame(gravity: Int = Gravity.NO_GRAVITY) =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            gravity,
        )

    /** Layout for a bar floating above the bottom edge, inset by [marginDp] on three sides. */
    fun bottomOverlayParams(
        context: Context,
        marginDp: Int = 16,
        bottomMarginDp: Int = 12,
    ) = FrameLayout
        .LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            marginStart = context.dp(marginDp)
            marginEnd = context.dp(marginDp)
            bottomMargin = context.dp(bottomMarginDp)
        }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** Draws behind the system bars with dark bar icons, as every Lenswave screen does. */
fun Activity.configureEdgeToEdgeWindow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

/** Keeps a bottom floating bar clear of the navigation bar and display cutouts. */
fun View.applyBottomOverlayInsets(
    insets: Insets,
    marginDp: Int = 8,
) {
    (layoutParams as FrameLayout.LayoutParams).apply {
        leftMargin = context.dp(marginDp) + insets.left
        rightMargin = context.dp(marginDp) + insets.right
        bottomMargin = context.dp(marginDp) + insets.bottom
        layoutParams = this
    }
}
