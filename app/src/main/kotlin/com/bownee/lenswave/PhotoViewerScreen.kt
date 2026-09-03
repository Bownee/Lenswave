package com.bownee.lenswave

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat

internal class PhotoViewerScreen(
    private val context: Context,
    requestIsTrashed: Boolean,
    actions: Actions,
) {
    val root = PhotoViewerGestureLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        gesturesEnabled = actions.gesturesEnabled
        onVerticalDrag = actions.onVerticalDrag
        onHorizontalDrag = actions.onHorizontalDrag
    }
    val backgroundScrim = View(context).apply { setBackgroundColor(Color.BLACK) }
    val photoDetailsScroll = PhotoDetailsScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    val photoDetailsSurface = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val mediaFrame = FrameLayout(context)
    val thumbnailPreview = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val photoView = FullResolutionPhotoView(context)
    val loadingPanel: LinearLayout
    val status: TextView
    val progress: ProgressBar
    val retryButton: Button
    val backButton: ImageButton
    val actions: LinearLayout
    val editButton: ImageButton
    val deleteButton: ImageButton
    val detailsSheet: LinearLayout
    val detailsContent: LinearLayout
    val detailsProgress: ProgressBar

    init {
        root.addView(backgroundScrim, matchParentFrame())

        mediaFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.resources.displayMetrics.heightPixels,
        )
        photoDetailsSurface.addView(mediaFrame)
        photoDetailsScroll.addView(
            photoDetailsSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(photoDetailsScroll, matchParentFrame())

        mediaFrame.addView(thumbnailPreview, photoLayoutParams())
        mediaFrame.addView(photoView, photoLayoutParams())

        progress = ProgressBar(context)
        status = TextView(context).apply {
            textSize = 13f
            setTextColor(UiStyle.muted)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        loadingPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(progress, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.dp(12) },
            )
            retryButton = Button(context).apply {
                setText(R.string.retry)
                isAllCaps = false
                visibility = View.GONE
                setOnClickListener { actions.onRetry() }
            }
            addView(retryButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48),
            ).apply { topMargin = context.dp(12) })
        }
        mediaFrame.addView(
            loadingPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        val details = buildDetailsSheet()
        detailsSheet = details.container
        detailsProgress = details.progress
        detailsContent = details.content
        photoDetailsSurface.addView(
            detailsSheet,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        backButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = UiStyle.rounded(context, Color.argb(220, 29, 33, 40), 18)
            contentDescription = context.getString(R.string.back)
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            setOnClickListener { actions.onBack() }
        }
        root.addView(
            backButton,
            FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.TOP or Gravity.START).apply {
                marginStart = context.dp(8)
                topMargin = context.dp(8)
            },
        )

        editButton = smallIconButton(context.getString(R.string.edit), R.drawable.ic_edit).apply {
            isEnabled = false
            setOnClickListener { actions.onEdit() }
        }
        deleteButton = smallIconButton(
            context.getString(if (requestIsTrashed) R.string.delete_forever else R.string.delete),
            R.drawable.ic_delete,
            destructive = true,
        ).apply {
            isEnabled = false
            setOnClickListener { actions.onDelete() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(editButton, LinearLayout.LayoutParams(
                context.dp(48),
                context.dp(48),
            ))
            addView(deleteButton, LinearLayout.LayoutParams(
                context.dp(48),
                context.dp(48),
            ).apply { marginStart = context.dp(8) })
        }
        this.actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = context.dp(64)
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(8))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            this.actions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                marginStart = context.dp(8)
                marginEnd = context.dp(8)
                bottomMargin = context.dp(8)
            },
        )

        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                actions.onLayoutChanged(bottom - top)
            }
        }
    }

    private fun buildDetailsSheet(): DetailsSheet {
        val progress = ProgressBar(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(8), 0, context.dp(12))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            visibility = View.INVISIBLE
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(10))
            background = UiStyle.rounded(context, Color.rgb(27, 30, 36), 24, stroke = null)
            addView(FrameLayout(context).apply {
                addView(View(context).apply {
                    background = UiStyle.rounded(context, UiStyle.muted, 3)
                }, FrameLayout.LayoutParams(context.dp(38), context.dp(4), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(26)))
            addView(TextView(context).apply {
                setText(R.string.photo_details)
                textSize = 20f
                setTextColor(UiStyle.text)
                setTypeface(typeface, Typeface.BOLD)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)))
            addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        return DetailsSheet(container, progress, content)
    }

    private fun smallIconButton(label: String, icon: Int, destructive: Boolean = false) =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(
                if (destructive) Color.rgb(255, 146, 146) else UiStyle.text,
            )
            contentDescription = label
            ViewCompat.setTooltipText(this, label)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            background = UiStyle.rounded(context, Color.argb(220, 29, 33, 40), 15)
        }

    private fun photoLayoutParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER,
    )

    private fun matchParentFrame() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    internal class Actions(
        val gesturesEnabled: () -> Boolean,
        val onVerticalDrag: (Float, Float, Boolean) -> Unit,
        val onHorizontalDrag: (Float, Boolean) -> Unit,
        val onBack: () -> Unit,
        val onEdit: () -> Unit,
        val onDelete: () -> Unit,
        val onRetry: () -> Unit,
        val onLayoutChanged: (Int) -> Unit,
    )

    private data class DetailsSheet(
        val container: LinearLayout,
        val progress: ProgressBar,
        val content: LinearLayout,
    )
}
