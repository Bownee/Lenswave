package com.bownee.lenswave.gallery

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.setPadding
import com.bownee.lenswave.R
import com.bownee.lenswave.UiStyle
import com.bownee.lenswave.dp
import com.bownee.lenswave.proton.ProtonAlbum

/** A photo thumbnail in the grid with its selection, loading and video overlays. */
internal class PhotoCell(
    context: Context,
) : FrameLayout(context) {
    /** The photo currently bound, read back by the shared click listeners and selection updates. */
    var asset: GalleryAsset? = null

    /** Set at bind when the cell already shows this photo, so a cache miss does not blank it. */
    var keepsShownImage = false
    lateinit var thumbnailTarget: GalleryThumbnailTarget
    val image =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(UiStyle.surfaceRaised)
        }
    val selectionScrim =
        View(context).apply {
            background = UiStyle.rounded(context, UiStyle.withAlpha(UiStyle.accent, 70), 4)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    val loading =
        ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(UiStyle.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
    val check =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(UiStyle.onAccent)
            setPadding(context.dp(5))
            background = UiStyle.circle(context, UiStyle.accent, Color.WHITE)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    val videoBadge =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(context.dp(8))
            background = UiStyle.circle(context, Color.argb(150, 10, 12, 18))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

    init {
        UiStyle.clipRounded(this, 4)
        setBackgroundColor(UiStyle.surface)
        addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(selectionScrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(loading, LayoutParams(context.dp(26), context.dp(26), Gravity.CENTER))
        addView(
            check,
            LayoutParams(context.dp(26), context.dp(26), Gravity.TOP or Gravity.START).apply {
                topMargin = context.dp(8)
                marginStart = context.dp(8)
            },
        )
        addView(videoBadge, LayoutParams(context.dp(36), context.dp(36), Gravity.CENTER))
    }
}

/** An album tile: rounded cover image above the album name and its details line. */
internal class AlbumCell(
    context: Context,
) : LinearLayout(context) {
    var album: ProtonAlbum? = null
    lateinit var thumbnailTarget: GalleryThumbnailTarget
    private val cover =
        FrameLayout(context).apply {
            UiStyle.clipRounded(this, 18)
            setBackgroundColor(UiStyle.surfaceRaised)
        }
    val image =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    val loading =
        ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(UiStyle.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
    val name =
        TextView(context).apply {
            setTextColor(UiStyle.text)
            textSize = 15f
            typeface = UiStyle.medium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(4), context.dp(10), context.dp(4), 0)
        }
    val details =
        TextView(context).apply {
            setTextColor(UiStyle.muted)
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(4), context.dp(2), context.dp(4), 0)
        }

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        cover.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        cover.addView(loading, FrameLayout.LayoutParams(context.dp(28), context.dp(28), Gravity.CENTER))
        addView(cover, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(details, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** A library entry: a tinted icon in a circle beside its label, in a rippled rounded pill. */
internal class EntryCell(
    context: Context,
) : LinearLayout(context) {
    var entry: LibraryItem.Entry? = null
    val icon =
        ImageView(context).apply {
            imageTintList = ColorStateList.valueOf(UiStyle.accent)
            setPadding(context.dp(8))
            background = UiStyle.circle(context, UiStyle.accentSoft)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    val label =
        TextView(context).apply {
            setTextColor(UiStyle.text)
            textSize = 15f
            typeface = UiStyle.medium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = UiStyle.rippled(UiStyle.rounded(context, UiStyle.surface, 20))
        setPadding(context.dp(12), 0, context.dp(14), 0)
        addView(icon, LayoutParams(context.dp(38), context.dp(38)).apply { marginEnd = context.dp(12) })
        addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
