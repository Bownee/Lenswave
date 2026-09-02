package com.bownee.lenswave

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.bownee.lenswave.proton.ProtonGalleryPhoto

internal class ProtonPhotoPickerScreen(
    private val activity: ProtonPhotoPickerActivity,
    adapter: BaseAdapter,
    actions: Actions,
) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        setBackgroundColor(UiStyle.background)
    }
    val connectButton: Button
    val disconnectButton: Button
    val status: TextView
    val progress: ProgressBar
    val retryButton: Button
    val grid: GridView

    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(activity.getString(R.string.space_proton), 25f, UiStyle.text).apply {
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(quietButton(activity.getString(R.string.close)).apply { setOnClickListener { actions.close() } })
        }
        root.addView(header, matchWrap())

        root.addView(text(
            activity.getString(R.string.proton_third_party_notice),
            12f,
            UiStyle.muted,
        ).apply {
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = UiStyle.rounded(activity, UiStyle.surface, 16)
        }, matchWrap().apply { topMargin = activity.dp(14) })

        connectButton = accentButton(activity.getString(R.string.connect_proton)).apply {
            setOnClickListener { actions.connect() }
        }
        disconnectButton = quietButton(activity.getString(R.string.disconnect)).apply {
            visibility = View.GONE
            setOnClickListener { actions.disconnect() }
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(connectButton, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
            addView(disconnectButton, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        }, matchWrap().apply { topMargin = activity.dp(12) })

        status = text(activity.getString(R.string.connect_to_load_timeline), 13f, UiStyle.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        retryButton = quietButton(activity.getString(R.string.retry)).apply {
            visibility = View.GONE
            setOnClickListener { actions.retry() }
        }
        progress = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(UiStyle.accent)
            visibility = View.GONE
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(48)))
            addView(progress, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)))
        }, matchWrap().apply {
            topMargin = activity.dp(14)
            bottomMargin = activity.dp(10)
        })

        grid = GridView(activity).apply {
            numColumns = 3
            horizontalSpacing = activity.dp(6)
            verticalSpacing = activity.dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            setPadding(0, 0, 0, activity.dp(8))
            selector = Color.TRANSPARENT.toDrawable()
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                actions.openPhoto(adapter.getItem(position) as ProtonGalleryPhoto)
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun accentButton(label: String) = Button(activity).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = UiStyle.rounded(activity, UiStyle.accentDark, 16, UiStyle.accent)
    }

    private fun quietButton(label: String) = Button(activity).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(UiStyle.text)
        background = UiStyle.rounded(activity, UiStyle.surface, 14)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    internal class Actions(
        val close: () -> Unit,
        val connect: () -> Unit,
        val disconnect: () -> Unit,
        val retry: () -> Unit,
        val openPhoto: (ProtonGalleryPhoto) -> Unit,
    )
}
