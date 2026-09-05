package com.bownee.lenswave.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.bownee.lenswave.R
import com.bownee.lenswave.UiStyle
import com.bownee.lenswave.dp
import com.bownee.lenswave.metadata.PhotoMetadataAction
import com.bownee.lenswave.metadata.PhotoMetadataItem

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PhotoViewerScreen(
    private val context: Context,
    callbacks: Actions,
) {
    val root =
        PhotoViewerGestureLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            gesturesEnabled = callbacks.gesturesEnabled
            gestureStartAllowed = callbacks.gestureStartAllowed
            onVerticalDrag = callbacks.onVerticalDrag
            onHorizontalDrag = callbacks.onHorizontalDrag
        }
    val backgroundScrim = View(context).apply { setBackgroundColor(Color.BLACK) }
    val photoDetailsScroll =
        PhotoDetailsScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    private val photoDetailsSurface = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val mediaFrame = FrameLayout(context)
    val thumbnailPreview =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** The neighbouring photo's thumbnail that follows a horizontal drag one screen away. */
    val peekPreview =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    val photoView = FullResolutionPhotoView(context)
    val playerView =
        PlayerView(context).apply {
            useController = true
            // Controls appear on tap only; showing them (and their dim scrim) while the video is still
            // preparing hides the picture behind them. The shutter stays transparent so the thumbnail
            // shows through until the first frame is decoded.
            controllerAutoShow = false
            controllerShowTimeoutMs = CONTROLS_TIMEOUT_MILLIS
            controllerHideOnTouch = true
            setShutterBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    val loadingPanel: LinearLayout
    val status: TextView
    val progress: ProgressBar
    val retryButton: Button
    val mediaTitle: TextView
    val actions: LinearLayout
    val favoriteButton: ImageButton
    val deleteButton: ImageButton
    val detailsSheet: LinearLayout
    val detailsContent: LinearLayout
    val detailsProgress: ProgressBar

    init {
        root.addView(backgroundScrim, matchParentFrame())

        mediaTitle =
            UiStyle.label(context, sizeSp = 15f, medium = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
                ViewCompat.setAccessibilityHeading(this, true)
            }

        mediaFrame.layoutParams =
            LinearLayout.LayoutParams(
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
        // The title floats over the media so the photo can centre on the full viewport.
        root.addView(
            mediaTitle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        mediaFrame.addView(peekPreview, photoLayoutParams())
        mediaFrame.addView(thumbnailPreview, photoLayoutParams())
        mediaFrame.addView(photoView, photoLayoutParams())
        mediaFrame.addView(playerView, photoLayoutParams())

        progress = ProgressBar(context)
        status =
            UiStyle.label(context, sizeSp = 13.5f, color = UiStyle.muted).apply {
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
        loadingPanel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                addView(progress, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
                addView(
                    status,
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = context.dp(12) },
                )
                retryButton =
                    UiStyle.accentButton(context, context.getString(R.string.retry), radiusDp = 24).apply {
                        setPadding(context.dp(24), 0, context.dp(24), 0)
                        visibility = View.GONE
                        setOnClickListener { callbacks.onRetry() }
                    }
                addView(
                    retryButton,
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            context.dp(48),
                        ).apply { topMargin = context.dp(14) },
                )
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

        favoriteButton =
            UiStyle
                .iconButton(
                    context,
                    R.drawable.ic_favorite_border,
                    context.getString(R.string.add_to_favorites),
                    fill = UiStyle.withAlpha(UiStyle.surfaceRaised, 235),
                    sizeDp = 52,
                ).apply {
                    isEnabled = false
                    visibility = View.GONE
                    ViewCompat.setTooltipText(this, contentDescription)
                    setOnClickListener { callbacks.onFavorite() }
                }
        deleteButton =
            UiStyle
                .iconButton(
                    context,
                    R.drawable.ic_delete,
                    context.getString(R.string.delete),
                    fill = UiStyle.withAlpha(UiStyle.dangerSoft, 240),
                    tint = UiStyle.danger,
                    sizeDp = 52,
                ).apply {
                    isEnabled = false
                    ViewCompat.setTooltipText(this, contentDescription)
                    setOnClickListener { callbacks.onDelete() }
                }
        val buttons =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(
                    favoriteButton,
                    LinearLayout
                        .LayoutParams(
                            context.dp(52),
                            context.dp(52),
                        ).apply { marginEnd = context.dp(12) },
                )
                addView(
                    deleteButton,
                    LinearLayout.LayoutParams(
                        context.dp(52),
                        context.dp(52),
                    ),
                )
            }
        this.actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = context.dp(64)
                setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(8))
                addView(
                    buttons,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        root.addView(this.actions, UiStyle.bottomOverlayParams(context, marginDp = 8, bottomMarginDp = 8))

        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                callbacks.onLayoutChanged(bottom - top)
            }
        }
        mediaTitle.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && root.height > 0) {
                callbacks.onLayoutChanged(root.height)
            }
        }
    }

    /** One background state shared by every details row; each row draws its own mutable copy. */
    private val detailsRowBackground: Drawable by lazy {
        UiStyle.rippled(UiStyle.rounded(context, Color.TRANSPARENT, 14))
    }

    /**
     * Shows [items] as the rows of the details sheet, rebinding the rows left from the previous
     * photo and creating more only when a photo has more rows than any before it; surplus rows are
     * hidden. Rows with a location open the map on tap.
     */
    fun bindDetailsRows(
        items: List<PhotoMetadataItem>,
        onOpenMap: (PhotoMetadataAction.OpenMap) -> Unit,
    ) {
        items.forEachIndexed { index, item ->
            val row = detailsContent.getChildAt(index) as? DetailsRow ?: newDetailsRow()
            row.bind(item, onOpenMap)
        }
        for (index in items.size until detailsContent.childCount) {
            detailsContent.getChildAt(index).visibility = View.GONE
        }
    }

    /** Hides every row so the sheet is empty for the next photo while keeping the views to rebind. */
    fun hideDetailsRows() {
        for (index in 0 until detailsContent.childCount) detailsContent.getChildAt(index).visibility = View.GONE
    }

    private fun newDetailsRow(): DetailsRow {
        val row = DetailsRow(context)
        row.background = detailsRowBackground.constantState?.newDrawable(context.resources)?.mutate()
            ?: UiStyle.rippled(UiStyle.rounded(context, Color.TRANSPARENT, 14))
        detailsContent.addView(
            row,
            LinearLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = context.dp(2)
                },
        )
        return row
    }

    private class DetailsRow(
        context: Context,
    ) : LinearLayout(context) {
        private val label =
            TextView(context).apply {
                textSize = 12f
                typeface = UiStyle.medium
                setTextColor(UiStyle.muted)
            }
        private val value =
            TextView(context).apply {
                textSize = 15.5f
                setTextColor(UiStyle.text)
                setPadding(0, context.dp(3), 0, 0)
            }
        private val mapLink =
            TextView(context).apply {
                setText(R.string.open_in_maps)
                textSize = 13f
                setTextColor(UiStyle.accent)
                setPadding(0, context.dp(7), 0, context.dp(2))
                visibility = View.GONE
            }

        init {
            orientation = VERTICAL
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            addView(label)
            addView(value)
            addView(mapLink)
        }

        fun bind(
            item: PhotoMetadataItem,
            onOpenMap: (PhotoMetadataAction.OpenMap) -> Unit,
        ) {
            label.text = item.label
            value.text = item.value
            val action = item.action as? PhotoMetadataAction.OpenMap
            if (action != null) {
                val open = OnClickListener { onOpenMap(action) }
                mapLink.setOnClickListener(open)
                setOnClickListener(open)
                mapLink.visibility = View.VISIBLE
            } else {
                mapLink.setOnClickListener(null)
                setOnClickListener(null)
                mapLink.visibility = View.GONE
            }
            mapLink.isClickable = action != null
            isClickable = action != null
            visibility = View.VISIBLE
        }
    }

    private fun buildDetailsSheet(): DetailsSheet {
        val progress = ProgressBar(context)
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, context.dp(8), 0, context.dp(12))
            }
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                alpha = 0f
                visibility = View.INVISIBLE
                setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(10))
                background = UiStyle.rounded(context, UiStyle.surface, 28, UiStyle.border)
                addView(
                    FrameLayout(context).apply {
                        addView(
                            View(context).apply {
                                background = UiStyle.rounded(context, UiStyle.border, 3)
                            },
                            FrameLayout.LayoutParams(context.dp(36), context.dp(4), Gravity.CENTER),
                        )
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(26)),
                )
                addView(
                    UiStyle.label(context, context.getString(R.string.photo_details), 20f, medium = true).apply {
                        ViewCompat.setAccessibilityHeading(this, true)
                    },
                    UiStyle.matchWrap(),
                )
                addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)))
                addView(content, UiStyle.matchWrap())
            }
        return DetailsSheet(container, progress, content)
    }

    private fun photoLayoutParams() = UiStyle.matchParentFrame(Gravity.CENTER)

    private fun matchParentFrame() = UiStyle.matchParentFrame()

    internal class Actions(
        val gesturesEnabled: () -> Boolean,
        val gestureStartAllowed: (Float, Float) -> Boolean,
        val onVerticalDrag: (Float, Float, Boolean) -> Unit,
        val onHorizontalDrag: (Float, Boolean) -> Unit,
        val onFavorite: () -> Unit,
        val onDelete: () -> Unit,
        val onRetry: () -> Unit,
        val onLayoutChanged: (Int) -> Unit,
    )

    private data class DetailsSheet(
        val container: LinearLayout,
        val progress: ProgressBar,
        val content: LinearLayout,
    )

    private companion object {
        const val CONTROLS_TIMEOUT_MILLIS = 2_500
    }
}
