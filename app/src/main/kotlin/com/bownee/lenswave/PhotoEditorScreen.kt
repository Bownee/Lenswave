package com.bownee.lenswave

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

internal class PhotoEditorScreen(
    val activity: PhotoEditorActivity,
    selectedAdjustment: Int,
    val actions: Actions,
) {
    val root = LinearLayout(activity)
    val adjustmentNames: Array<String> = activity.resources.getStringArray(R.array.adjustment_names)
    val lookNames: Array<String> = activity.resources.getStringArray(R.array.look_names)
    val adjustmentButtons: Array<Button?> = arrayOfNulls(8)
    val lookButtons: Array<Button?> = arrayOfNulls(4)
    lateinit var photoView: PhotoGLSurfaceView
    val progressBar: ProgressBar
    lateinit var emptyState: View
    lateinit var lookStatus: TextView
    lateinit var adjustmentLabel: TextView
    lateinit var adjustmentValue: TextView
    lateinit var adjustmentSlider: SeekBar
    lateinit var adjustPanel: LinearLayout
    lateinit var looksPanel: LinearLayout
    lateinit var adjustTab: Button
    lateinit var looksTab: Button
    lateinit var photoButton: ImageButton
    lateinit var emptyPhotoButton: Button
    lateinit var saveButton: Button
    lateinit var undoButton: ImageButton
    lateinit var rotateButton: ImageButton
    lateinit var resetButton: ImageButton
    lateinit var beforeButton: ImageButton

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(COLOR_BACKGROUND)
        root.setPadding(dp(12), dp(8), dp(12), dp(10))
        root.addView(buildHeader(), matchWrap())

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            contentDescription = activity.getString(R.string.rendering_full_size)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_ACCENT)
            visibility = View.INVISIBLE
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(6) },
        )

        root.addView(
            buildCanvas(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            },
        )

        val editorScroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                buildEditorPanel(selectedAdjustment),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        root.addView(editorScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f))
        root.addView(buildBottomBar(), matchWrap().apply { topMargin = dp(8) })
    }

    private fun buildHeader(): View = text(activity.getString(R.string.app_name), 24f, COLOR_TEXT).apply {
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = -0.02f
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun buildCanvas(): FrameLayout = FrameLayout(activity).apply {
        background = solidBackground(Color.rgb(6, 7, 10), dp(26), COLOR_BORDER)
        clipToOutline = true

        photoView = PhotoGLSurfaceView(activity).apply {
            contentDescription = activity.getString(R.string.editor_image)
        }
        addView(
            photoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val empty = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        empty.addView(text("✦", 34f, COLOR_ACCENT).apply { gravity = Gravity.CENTER }, matchWrap())
        empty.addView(
            text(activity.getString(R.string.edit_one_photo), 20f, COLOR_TEXT).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            matchWrap().apply { topMargin = dp(5) },
        )
        empty.addView(
            text(activity.getString(R.string.editor_original_untouched), 13f, COLOR_MUTED).apply {
                gravity = Gravity.CENTER
            },
            matchWrap().apply { topMargin = dp(5) },
        )
        emptyPhotoButton = accentButton(activity.getString(R.string.choose_photo)).apply {
            setOnClickListener { actions.choosePhoto() }
        }
        empty.addView(
            emptyPhotoButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(18)
            },
        )
        emptyState = empty
        addView(
            empty,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    private fun buildEditorPanel(selectedAdjustment: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(10))
        background = solidBackground(COLOR_SURFACE, dp(24), COLOR_BORDER)

        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = solidBackground(COLOR_BACKGROUND, dp(16), Color.TRANSPARENT)
        }
        adjustTab = tabButton(activity.getString(R.string.adjust)).apply {
            setOnClickListener { actions.showSection(0) }
        }
        tabs.addView(adjustTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        looksTab = tabButton(activity.getString(R.string.looks)).apply {
            setOnClickListener { actions.showSection(1) }
        }
        tabs.addView(looksTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(tabs, matchWrap())

        adjustPanel = buildAdjustPanel(selectedAdjustment)
        addView(adjustPanel, matchWrap().apply { topMargin = dp(8) })
        looksPanel = buildLooksPanel()
        addView(looksPanel, matchWrap().apply { topMargin = dp(8) })
    }

    private fun buildAdjustPanel(selectedAdjustment: Int): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val valueRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        adjustmentLabel = text(adjustmentNames[selectedAdjustment], 14f, COLOR_TEXT).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        adjustmentValue = text("0", 14f, COLOR_ACCENT).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.END
        }
        valueRow.addView(adjustmentLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        valueRow.addView(adjustmentValue, wrapWrap())
        addView(valueRow, matchWrap())

        adjustmentSlider = SeekBar(activity).apply {
            max = 200
            progress = 100
            minimumHeight = dp(48)
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_BORDER)
            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) actions.changeAdjustment(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = actions.startAdjustment()

                override fun onStopTrackingTouch(seekBar: SeekBar) = actions.finishAdjustment()
            })
        }
        addView(adjustmentSlider, matchWrap())

        val tools = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        adjustmentNames.forEachIndexed { index, name ->
            val tool = chipButton(name).apply { setOnClickListener { actions.selectAdjustment(index) } }
            adjustmentButtons[index] = tool
            tools.addView(tool, chipLayout())
        }
        addView(horizontalScroll(tools), matchWrap().apply { topMargin = dp(12) })
    }

    private fun buildLooksPanel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            text(activity.getString(R.string.one_tap_looks), 14f, COLOR_TEXT).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            },
            matchWrap(),
        )
        lookStatus = text(activity.getString(R.string.choose_look_prompt), 12f, COLOR_MUTED)
        addView(lookStatus, matchWrap().apply { topMargin = dp(2) })

        val looks = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        lookNames.forEachIndexed { index, name ->
            val look = chipButton(name).apply { setOnClickListener { actions.applyLook(index) } }
            lookButtons[index] = look
            looks.addView(look, chipLayout())
        }
        addView(horizontalScroll(looks), matchWrap().apply { topMargin = dp(8) })
    }

    private fun buildBottomBar(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        photoButton = iconButton(R.drawable.ic_photo, R.string.choose_photo).apply {
            setOnClickListener { actions.choosePhoto() }
        }
        addView(photoButton, iconActionLayout())
        rotateButton = iconButton(R.drawable.ic_rotate, R.string.rotate_photo).apply {
            setOnClickListener { actions.rotate() }
        }
        addView(rotateButton, iconActionLayout())
        undoButton = iconButton(R.drawable.ic_undo, R.string.undo_edit).apply {
            setOnClickListener { actions.undo() }
        }
        addView(undoButton, iconActionLayout())
        resetButton = iconButton(R.drawable.ic_reset, R.string.reset_edits).apply {
            setOnClickListener { actions.reset() }
        }
        addView(resetButton, iconActionLayout())
        beforeButton = iconButton(R.drawable.ic_before, R.string.show_original_toggle).apply {
            setOnClickListener { view ->
                if (!actions.canShowOriginal()) return@setOnClickListener
                val show = !view.isSelected
                setOriginalShown(show)
                actions.showOriginal(show)
            }
        }
        addView(beforeButton, iconActionLayout())
        saveButton = accentButton(activity.getString(R.string.save_copy)).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { actions.save() }
        }
        addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
    }

    fun updateTabAppearance(tab: Button, selected: Boolean) {
        tab.isSelected = selected
        applyButtonAppearance(
            tab,
            if (selected) COLOR_SURFACE_RAISED else Color.TRANSPARENT,
            if (selected) COLOR_TEXT else COLOR_MUTED,
            Color.TRANSPARENT,
            dp(13),
        )
    }

    fun setOriginalShown(show: Boolean) {
        beforeButton.isSelected = show
        ViewCompat.setStateDescription(
            beforeButton,
            activity.getString(if (show) R.string.showing_original else R.string.showing_edits),
        )
    }

    fun applyButtonAppearance(button: Button, fillColor: Int, textColor: Int, strokeColor: Int, radius: Int) {
        button.setTextColor(textColor)
        button.background = rippleBackground(fillColor, radius, strokeColor)
    }

    private fun tabButton(label: String): Button = styleButton(
        Button(activity),
        label,
        Color.TRANSPARENT,
        COLOR_MUTED,
        Color.TRANSPARENT,
        dp(13),
    ).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }

    private fun chipButton(label: String): Button = styleButton(
        Button(activity),
        label,
        COLOR_SURFACE_RAISED,
        COLOR_TEXT,
        COLOR_BORDER,
        dp(14),
    ).apply { textSize = 12f }

    private fun accentButton(label: String): Button = styleButton(
        Button(activity),
        label,
        COLOR_ACCENT,
        Color.rgb(24, 20, 41),
        COLOR_ACCENT,
        dp(16),
    ).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }

    private fun iconButton(drawable: Int, description: Int): ImageButton = ImageButton(activity).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(COLOR_TEXT)
        contentDescription = activity.getString(description)
        tooltipText = activity.getString(description)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = rippleBackground(COLOR_SURFACE_RAISED, dp(14), COLOR_BORDER)
        stateListAnimator = null
        elevation = 0f
    }

    private fun styleButton(
        button: Button,
        label: String,
        fillColor: Int,
        textColor: Int,
        strokeColor: Int,
        radius: Int,
    ): Button = button.apply {
        text = label
        textSize = 13f
        isAllCaps = false
        isSingleLine = true
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
        stateListAnimator = null
        elevation = 0f
        applyButtonAppearance(this, fillColor, textColor, strokeColor, radius)
    }

    private fun rippleBackground(fillColor: Int, radius: Int, strokeColor: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x33ffffff),
        solidBackground(fillColor, radius, strokeColor),
        null,
    )

    private fun solidBackground(fillColor: Int, radius: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius.toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }

    private fun horizontalScroll(row: View): HorizontalScrollView = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun chipLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(7) }

    private fun iconActionLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) }

    private fun text(value: String, size: Float, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    interface Actions {
        fun choosePhoto()
        fun showSection(section: Int)
        fun selectAdjustment(adjustment: Int)
        fun changeAdjustment(progress: Int)
        fun startAdjustment()
        fun finishAdjustment()
        fun applyLook(look: Int)
        fun rotate()
        fun undo()
        fun reset()
        fun canShowOriginal(): Boolean
        fun showOriginal(show: Boolean)
        fun save()
    }

    companion object {
        val COLOR_BACKGROUND = UiStyle.background
        val COLOR_SURFACE = UiStyle.surface
        val COLOR_SURFACE_RAISED = UiStyle.surfaceRaised
        val COLOR_BORDER = UiStyle.border
        val COLOR_TEXT = UiStyle.text
        val COLOR_MUTED = UiStyle.muted
        val COLOR_ACCENT = UiStyle.accent
        val COLOR_ACCENT_DARK = UiStyle.accentDark
    }
}
