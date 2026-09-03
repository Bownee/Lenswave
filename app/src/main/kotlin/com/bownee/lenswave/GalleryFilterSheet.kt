package com.bownee.lenswave

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bownee.lenswave.gallery.DeviceCollection
import com.bownee.lenswave.gallery.GalleryPhotoFilters
import com.bownee.lenswave.gallery.GallerySourceFilter
import com.bownee.lenswave.gallery.PhotoSource
import com.bownee.lenswave.proton.ProtonMediaTag
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

internal object GalleryFilterSheet {
    fun showPhotos(
        context: Context,
        initial: GalleryPhotoFilters,
        onApply: (GalleryPhotoFilters) -> Unit,
    ) {
        var selectedSource = initial.source
        var selectedTag = initial.protonTag
        var selectedCollection = initial.deviceCollection
        var updatingSource = false

        val content = content(context, R.string.filter_photos)
        content.addView(sectionLabel(context, R.string.filter_source))
        val sourceGroup = choiceGroup(context)
        val sourceIds = GallerySourceFilter.entries.associateWith { source ->
            sourceGroup.addChoice(context, sourceLabel(context, source), selectedSource == source)
        }
        content.addView(sourceGroup, matchWidth())

        val mediaLabel = sectionLabel(context, R.string.filter_media_type)
        content.addView(mediaLabel)
        val mediaGroup = choiceGroup(context)
        content.addView(mediaGroup, matchWidth())

        fun rebuildMediaChoices() {
            mediaGroup.setOnCheckedChangeListener(null)
            mediaGroup.removeAllViews()
            val choices = mutableMapOf<Int, MediaChoice>()
            if (selectedSource == GallerySourceFilter.DEVICE) {
                DeviceCollection.entries.forEach { collection ->
                    val id = mediaGroup.addChoice(
                        context,
                        if (collection == DeviceCollection.ALL) {
                            context.getString(R.string.proton_filter_all)
                        } else {
                            context.getString(collection.labelRes)
                        },
                        collection == selectedCollection,
                    )
                    choices[id] = MediaChoice.Device(collection)
                }
            } else {
                val allId = mediaGroup.addChoice(
                    context,
                    context.getString(R.string.proton_filter_all),
                    selectedTag == null,
                )
                choices[allId] = MediaChoice.All
                ProtonMediaTag.entries.forEach { tag ->
                    val id = mediaGroup.addChoice(
                        context,
                        context.getString(tag.labelRes),
                        selectedTag == tag,
                    )
                    choices[id] = MediaChoice.Proton(tag)
                }
            }
            mediaGroup.setOnCheckedChangeListener { _, checkedId ->
                when (val choice = choices[checkedId]) {
                    MediaChoice.All -> selectedTag = null
                    is MediaChoice.Device -> selectedCollection = choice.collection
                    is MediaChoice.Proton -> {
                        selectedTag = choice.tag
                        if (selectedSource == GallerySourceFilter.ALL) {
                            selectedSource = GallerySourceFilter.PROTON
                            updatingSource = true
                            sourceGroup.check(sourceIds.getValue(GallerySourceFilter.PROTON))
                            updatingSource = false
                        }
                    }
                    null -> Unit
                }
            }
        }

        sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingSource) return@setOnCheckedChangeListener
            selectedSource = sourceIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            if (selectedSource != GallerySourceFilter.PROTON) selectedTag = null
            rebuildMediaChoices()
        }
        rebuildMediaChoices()

        val dialog = createDialog(context, content)
        content.addView(doneButton(context) {
            onApply(
                GalleryPhotoFilters(
                    source = selectedSource,
                    protonTag = selectedTag,
                    deviceCollection = selectedCollection,
                ),
            )
            dialog.dismiss()
        })
        show(dialog)
    }

    fun showTrash(
        context: Context,
        initial: PhotoSource,
        supportsDeviceTrash: Boolean,
        onApply: (PhotoSource) -> Unit,
    ) {
        var selectedSource = initial
        val content = content(context, R.string.filter_trash)
        content.addView(sectionLabel(context, R.string.filter_source))
        val sourceGroup = choiceGroup(context)
        val choices = buildMap<Int, PhotoSource> {
            put(
                sourceGroup.addChoice(
                    context,
                    context.getString(R.string.filter_source_proton),
                    initial == PhotoSource.PROTON,
                ),
                PhotoSource.PROTON,
            )
            if (supportsDeviceTrash) {
                put(
                    sourceGroup.addChoice(
                        context,
                        context.getString(R.string.filter_source_device),
                        initial == PhotoSource.DEVICE,
                    ),
                    PhotoSource.DEVICE,
                )
            }
        }
        sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            choices[checkedId]?.let { selectedSource = it }
        }
        content.addView(sourceGroup, matchWidth())

        val dialog = createDialog(context, content)
        content.addView(doneButton(context) {
            onApply(selectedSource)
            dialog.dismiss()
        })
        show(dialog)
    }

    private fun content(context: Context, titleRes: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(24))
        background = UiStyle.rounded(context, UiStyle.surface, 24, stroke = null)
        addView(View(context).apply {
            background = UiStyle.rounded(context, UiStyle.muted, 3, stroke = null)
        }, LinearLayout.LayoutParams(context.dp(38), context.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(18)
        })
        addView(TextView(context).apply {
            setText(titleRes)
            textSize = 22f
            setTextColor(UiStyle.text)
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        }, matchWidth().apply { bottomMargin = context.dp(14) })
    }

    private fun sectionLabel(context: Context, labelRes: Int) = TextView(context).apply {
        setText(labelRes)
        textSize = 13f
        setTextColor(UiStyle.muted)
        setPadding(context.dp(2), context.dp(12), 0, context.dp(7))
    }

    private fun choiceGroup(context: Context) = RadioGroup(context).apply {
        orientation = RadioGroup.VERTICAL
        background = UiStyle.rounded(context, UiStyle.surfaceRaised, 14, UiStyle.border)
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
    }

    private fun RadioGroup.addChoice(
        context: Context,
        label: String,
        checked: Boolean,
    ): Int {
        val id = View.generateViewId()
        addView(RadioButton(context).apply {
            this.id = id
            text = label
            textSize = 15f
            setTextColor(UiStyle.text)
            buttonTintList = ColorStateList.valueOf(UiStyle.accent)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = context.dp(48)
            setPadding(context.dp(8), 0, context.dp(8), 0)
            isChecked = checked
        }, matchWidth())
        return id
    }

    private fun doneButton(context: Context, onClick: () -> Unit) = Button(context).apply {
        setText(R.string.done)
        textSize = 15f
        setTextColor(Color.rgb(28, 21, 56))
        isAllCaps = false
        background = UiStyle.rounded(context, UiStyle.accent, 14, stroke = null)
        setOnClickListener { onClick() }
        layoutParams = matchWidth(context.dp(50)).apply { topMargin = context.dp(16) }
    }

    private fun sourceLabel(context: Context, source: GallerySourceFilter): String = context.getString(
        when (source) {
            GallerySourceFilter.ALL -> R.string.filter_all_sources
            GallerySourceFilter.PROTON -> R.string.filter_source_proton
            GallerySourceFilter.DEVICE -> R.string.filter_source_device
        },
    )

    private fun createDialog(context: Context, content: View): BottomSheetDialog =
        BottomSheetDialog(context).apply {
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(content)
            }
            setContentView(scroll)
            setOnShowListener {
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(Color.TRANSPARENT)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                view.setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(24) + bottom)
                insets
            }
        }

    private fun show(dialog: BottomSheetDialog) {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
        }
    }

    private fun matchWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private sealed interface MediaChoice {
        data object All : MediaChoice
        data class Proton(val tag: ProtonMediaTag) : MediaChoice
        data class Device(val collection: DeviceCollection) : MediaChoice
    }
}
