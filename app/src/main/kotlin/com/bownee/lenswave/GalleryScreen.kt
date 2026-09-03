package com.bownee.lenswave

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.TextViewCompat
import com.bownee.lenswave.gallery.DeviceCollection
import com.bownee.lenswave.gallery.DeviceCollectionPicker
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.GalleryListAdapter
import com.bownee.lenswave.gallery.GalleryListView
import com.bownee.lenswave.gallery.GalleryScrollPosition
import com.bownee.lenswave.gallery.GalleryThumbnailLoader
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonPhotoGateway
import kotlinx.coroutines.CoroutineScope
import androidx.core.view.ViewCompat
import me.proton.core.domain.entity.UserId

internal class GalleryScreen(
    private val activity: GalleryActivity,
    scope: CoroutineScope,
    repository: ProtonPhotoGateway,
    currentUserId: () -> UserId?,
    currentDestination: () -> GalleryDestination,
    private val actions: Actions,
) {
    val root = FrameLayout(activity).apply { setBackgroundColor(UiStyle.background) }
    val pageTitle: TextView
    private val status: TextView
    val list: GalleryListView
    val galleryHeader: LinearLayout
    val galleryFooter: View
    private val emptyPanel: LinearLayout
    private val emptyTitle: TextView
    private val emptyMessage: TextView
    private val emptyAction: Button
    val adapter: GalleryListAdapter
    val stickyMonth: TextView
    val sourceBar: LinearLayout
    val selectionBar: LinearLayout
    private val selectionCount: TextView
    private val selectionDeleteButton: Button
    val settingsButton: ImageButton
    val refreshButton: ImageButton
    val albumBackButton: ImageButton
    val trashDeleteAllButton: Button
    val protonSourceButton: Button
    val albumsSourceButton: Button
    val deviceSourceButton: Button
    val trashSourceButton: Button
    val devicePicker: LinearLayout
    val devicePickerButtons = mutableMapOf<DeviceCollection, Button>()

    private var pendingStickyMonthPosition: Int? = null
    private var stickyMonthUpdatePosted = false
    private var visibleThumbnailUpdatePosted = false
    private var lastVisibleThumbnailNodeUids: Set<String> = emptySet()
    private val stickyMonthUpdate = Runnable {
        stickyMonthUpdatePosted = false
        val position = pendingStickyMonthPosition ?: return@Runnable
        pendingStickyMonthPosition = null
        updateStickyMonth(position)
    }
    private val visibleThumbnailUpdate = Runnable {
        visibleThumbnailUpdatePosted = false
        val headerCount = list.headerViewsCount
        val firstRow = (list.firstVisiblePosition - headerCount).coerceAtLeast(0)
        val lastRowExclusive = (list.lastVisiblePosition - headerCount + 1).coerceAtLeast(firstRow)
        val nodeUids = adapter.pendingProtonThumbnailNodeUids(firstRow, lastRowExclusive - firstRow)
        if (nodeUids != lastVisibleThumbnailNodeUids) {
            lastVisibleThumbnailNodeUids = nodeUids
            actions.onVisibleThumbnailsChanged(nodeUids)
        }
    }

    init {
        val header = buildGalleryHeader()
        galleryHeader = header.container
        pageTitle = header.pageTitle
        status = header.status
        settingsButton = header.settingsButton
        refreshButton = header.refreshButton
        albumBackButton = header.albumBackButton
        trashDeleteAllButton = header.trashDeleteAllButton
        emptyPanel = header.emptyPanel
        emptyTitle = header.emptyTitle
        emptyMessage = header.emptyMessage
        emptyAction = header.emptyAction

        adapter = GalleryListAdapter(
            context = activity,
            thumbnailLoader = GalleryThumbnailLoader(
                context = activity,
                scope = scope,
                protonRepository = repository,
                protonUserId = currentUserId,
            ),
            onPhotoClicked = actions.onPhotoClicked,
            onAlbumClicked = actions.onAlbumClicked,
            onSelectionChanged = actions.onSelectionChanged,
            currentDestination = currentDestination,
        )
        galleryFooter = View(activity).apply {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        list = GalleryListView(activity).apply {
            applyDraggableFastScroll()
            divider = null
            dividerHeight = 0
            setHeaderDividersEnabled(false)
            setFooterDividersEnabled(false)
            clipToPadding = false
            selector = Color.TRANSPARENT.toDrawable()
            addHeaderView(galleryHeader, null, false)
            addFooterView(galleryFooter, null, false)
            adapter = this@GalleryScreen.adapter
        }
        root.addView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        stickyMonth = text("", 15f, UiStyle.text).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
            background = UiStyle.rounded(activity, Color.argb(236, 20, 23, 28), 20)
            elevation = activity.dp(6).toFloat()
            visibility = View.GONE
        }
        root.addView(
            stickyMonth,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                activity.dp(40),
                Gravity.TOP or Gravity.START,
            ),
        )

        val source = buildSourceBar()
        sourceBar = source.container
        protonSourceButton = source.proton
        albumsSourceButton = source.albums
        deviceSourceButton = source.device
        trashSourceButton = source.trash
        root.addView(sourceBar, bottomOverlayParams())

        devicePicker = buildDevicePicker().apply { visibility = View.GONE }
        root.addView(devicePicker, devicePickerParams())
        sourceBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            devicePicker.post(actions.onSourceBarLayout)
        }

        val selection = buildSelectionBar()
        selectionBar = selection.container.apply { visibility = View.GONE }
        selectionCount = selection.count
        selectionDeleteButton = selection.delete
        root.addView(selectionBar, bottomOverlayParams())

        attachScrollListeners()
    }

    fun dispose() {
        list.removeCallbacks(stickyMonthUpdate)
        list.removeCallbacks(visibleThumbnailUpdate)
        pendingStickyMonthPosition = null
        stickyMonthUpdatePosted = false
        visibleThumbnailUpdatePosted = false
    }

    fun scheduleVisibleThumbnailUpdate() {
        if (visibleThumbnailUpdatePosted) return
        visibleThumbnailUpdatePosted = true
        list.postOnAnimation(visibleThumbnailUpdate)
    }

    fun retryVisibleThumbnailDownloads() {
        lastVisibleThumbnailNodeUids = emptySet()
        scheduleVisibleThumbnailUpdate()
    }

    fun captureScrollPosition(): GalleryScrollPosition? {
        val firstVisibleChild = list.getChildAt(0) ?: return null
        return GalleryScrollPosition(list.firstVisiblePosition, firstVisibleChild.top)
    }

    fun restoreScrollPosition(
        position: GalleryScrollPosition,
        isCurrentDestination: () -> Boolean,
    ) {
        list.post {
            if (!isCurrentDestination()) return@post
            val maximumPosition = (list.count - 1).coerceAtLeast(0)
            list.setSelectionFromTop(
                position.firstVisiblePosition.coerceIn(0, maximumPosition),
                position.topOffset,
            )
            lastVisibleThumbnailNodeUids = emptySet()
            list.post {
                if (!isCurrentDestination()) return@post
                scheduleStickyMonthUpdate(list.firstVisiblePosition)
                scheduleVisibleThumbnailUpdate()
            }
        }
    }

    fun renderHeader(statusText: String, showDeleteAll: Boolean, refreshing: Boolean) {
        status.text = statusText
        refreshButton.isEnabled = !refreshing
        refreshButton.alpha = if (refreshing) 0.5f else 1f
        trashDeleteAllButton.visibility = if (showDeleteAll) View.VISIBLE else View.GONE
        trashDeleteAllButton.isEnabled = !refreshing
        trashDeleteAllButton.alpha = if (refreshing) 0.5f else 1f
    }

    fun showContent() {
        emptyPanel.visibility = View.GONE
    }

    fun showEmptyState(
        title: String,
        message: String,
        action: String?,
        onAction: (() -> Unit)?,
    ) {
        emptyPanel.visibility = View.VISIBLE
        emptyTitle.text = title
        emptyMessage.text = message
        emptyAction.visibility = if (action == null) View.GONE else View.VISIBLE
        emptyAction.text = action
        emptyAction.setOnClickListener(if (onAction == null) null else View.OnClickListener { onAction() })
    }

    fun renderSelection(selectedCount: Int, viewingTrash: Boolean, showDeleteAll: Boolean) {
        val selecting = selectedCount > 0
        if (selecting) devicePicker.visibility = View.GONE
        selectionCount.text = activity.resources.getQuantityString(
            R.plurals.selected_photo_count,
            selectedCount,
            selectedCount,
        )
        selectionDeleteButton.setText(if (viewingTrash) R.string.delete_forever else R.string.delete)
        if (viewingTrash) {
            trashDeleteAllButton.visibility = if (!selecting && showDeleteAll) View.VISIBLE else View.GONE
        }
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
    }

    fun updateStickyMonthMargins(top: Int, start: Int) {
        (stickyMonth.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = top
            marginStart = start
            stickyMonth.layoutParams = this
        }
    }

    private fun attachScrollListeners() {
        list.setOnFastScrollInteractionListener { active ->
            adapter.setFastScrolling(active)
            if (!active) {
                scheduleStickyMonthUpdate(list.firstVisiblePosition)
                scheduleVisibleThumbnailUpdate()
            }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                scheduleStickyMonthUpdate(firstVisibleItem)
                scheduleVisibleThumbnailUpdate()
            }
        })
    }

    private fun scheduleStickyMonthUpdate(firstVisibleItem: Int) {
        pendingStickyMonthPosition = firstVisibleItem
        if (stickyMonthUpdatePosted) return
        stickyMonthUpdatePosted = true
        list.postOnAnimation(stickyMonthUpdate)
    }

    private fun updateStickyMonth(firstVisibleItem: Int) {
        if (firstVisibleItem < list.headerViewsCount) {
            stickyMonth.visibility = View.GONE
            return
        }
        val position = firstVisibleItem - list.headerViewsCount
        val label = adapter.monthLabelForPosition(position)
        if (label == null) {
            stickyMonth.visibility = View.GONE
        } else {
            stickyMonth.text = label
            stickyMonth.visibility = View.VISIBLE
        }
    }

    private fun buildGalleryHeader(): Header {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val albumBack = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(UiStyle.text)
            background = Color.TRANSPARENT.toDrawable()
            contentDescription = activity.getString(R.string.back_to_albums)
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            visibility = View.GONE
            setOnClickListener {
                if (adapter.selectedPhotos().isNotEmpty()) adapter.clearSelection() else actions.onAlbumBack()
            }
        }
        titleRow.addView(albumBack, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
            marginEnd = activity.dp(4)
        })
        val title = text(activity.getString(R.string.photos), 30f, UiStyle.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setOnClickListener { actions.onSpaceMenu() }
            ViewCompat.setAccessibilityHeading(this, true)
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(UiStyle.text)
            background = UiStyle.rounded(activity, UiStyle.surface, 16)
            contentDescription = activity.getString(R.string.settings)
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            setOnClickListener { actions.onSettings() }
        }
        val refresh = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_refresh)
            imageTintList = ColorStateList.valueOf(UiStyle.text)
            background = UiStyle.rounded(activity, UiStyle.surface, 16)
            contentDescription = activity.getString(R.string.refresh)
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            setOnClickListener { actions.onRefresh() }
        }
        titleRow.addView(refresh, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
            marginEnd = activity.dp(8)
        })
        titleRow.addView(settings, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        container.addView(titleRow, matchWrap())

        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(28)
        }
        val status = text("", 13f, UiStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val deleteAll = iconLabelButton(activity.getString(R.string.delete_all), R.drawable.ic_delete).apply {
            textSize = 12f
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            visibility = View.GONE
            setOnClickListener { actions.onDeleteAllTrash() }
        }
        statusRow.addView(
            deleteAll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(48)).apply {
                marginEnd = activity.dp(20)
            },
        )
        container.addView(statusRow, matchWrap().apply {
            topMargin = activity.dp(14)
            bottomMargin = activity.dp(8)
        })

        val empty = buildEmptyPanel()
        container.addView(empty.container, matchWrap().apply {
            topMargin = activity.dp(10)
            bottomMargin = activity.dp(12)
        })
        return Header(container, title, status, settings, refresh, albumBack, deleteAll, empty)
    }

    private fun buildEmptyPanel(): EmptyPanel {
        val title = text("", 20f, UiStyle.text).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val message = text("", 13f, UiStyle.muted).apply { gravity = Gravity.CENTER }
        val action = accentButton(activity.getString(R.string.continue_action)).apply { visibility = View.GONE }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(activity.dp(24), activity.dp(28), activity.dp(24), activity.dp(28))
            background = UiStyle.rounded(activity, UiStyle.surface, 22)
            addView(title, matchWrap())
            addView(message, matchWrap().apply { topMargin = activity.dp(8) })
            addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(48)).apply {
                topMargin = activity.dp(18)
            })
        }
        return EmptyPanel(container, title, message, action)
    }

    private fun buildSourceBar(): SourceBar {
        val proton = sectionButton(activity.getString(R.string.photos)).apply { setOnClickListener { actions.onProtonSource() } }
        val albums = sectionButton(activity.getString(R.string.albums)).apply { setOnClickListener { actions.onAlbumsSource() } }
        val device = sectionButton(activity.getString(R.string.source_dropdown_label, activity.getString(R.string.collection_camera))).apply {
            contentDescription = activity.getString(R.string.choose_device_collection, activity.getString(R.string.collection_camera))
            setOnClickListener { actions.onDeviceSource() }
        }
        val trash = sectionButton(activity.getString(R.string.trash)).apply { setOnClickListener { actions.onTrashSource() } }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5))
            background = UiStyle.rounded(activity, Color.argb(246, 29, 33, 40), 22, UiStyle.border)
            addView(proton, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
            addView(albums, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply {
                marginStart = activity.dp(3)
            })
            addView(device, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
            addView(trash, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply {
                marginStart = activity.dp(3)
            })
        }
        return SourceBar(container, proton, albums, device, trash)
    }

    private fun buildDevicePicker() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
        background = UiStyle.rounded(activity, Color.argb(250, 29, 33, 40), 20)
        DeviceCollectionPicker.collections.forEach { collection ->
            val button = sectionButton(activity.getString(DeviceCollectionPicker.menuLabelRes(collection))).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                elevation = 0f
                stateListAnimator = null
                setOnClickListener { actions.onDeviceCollection(collection) }
            }
            devicePickerButtons[collection] = button
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(48)))
        }
    }

    private fun buildSelectionBar(): SelectionBar {
        val count = text(activity.resources.getQuantityString(R.plurals.selected_photo_count, 0, 0), 15f, UiStyle.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(12), 0, activity.dp(8), 0)
        }
        val delete = iconLabelButton(activity.getString(R.string.delete), R.drawable.ic_delete, destructive = true).apply {
            setOnClickListener { actions.onDeleteSelection() }
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
            background = UiStyle.rounded(activity, Color.argb(250, 29, 33, 40), 22, UiStyle.border)
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(UiStyle.text)
                background = UiStyle.rounded(activity, UiStyle.surfaceRaised, 15)
                contentDescription = activity.getString(R.string.cancel_selection)
                setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
                setOnClickListener { adapter.clearSelection() }
            }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
            addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(delete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(48)))
        }
        return SelectionBar(container, count, delete)
    }

    private fun sectionButton(label: String) = Button(activity).apply {
        text = label
        textSize = 13f
        setTextColor(UiStyle.muted)
        isAllCaps = false
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
        background = Color.TRANSPARENT.toDrawable()
    }

    private fun iconLabelButton(label: String, icon: Int, destructive: Boolean = false) =
        Button(activity).apply {
            text = label
            textSize = 13f
            setTextColor(if (destructive) Color.rgb(255, 146, 146) else UiStyle.text)
            isAllCaps = false
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(
                if (destructive) Color.rgb(255, 146, 146) else UiStyle.text
            ))
            compoundDrawablePadding = activity.dp(7)
            setPadding(activity.dp(14), 0, activity.dp(14), 0)
            background = UiStyle.rounded(activity, UiStyle.surfaceRaised, 15)
        }

    private fun accentButton(label: String) = Button(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = UiStyle.rounded(activity, UiStyle.accentDark, 15, null)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun bottomOverlayParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM,
    ).apply {
        marginStart = activity.dp(16)
        marginEnd = activity.dp(16)
        bottomMargin = activity.dp(12)
    }

    private fun devicePickerParams() = FrameLayout.LayoutParams(
        activity.dp(188),
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.START,
    ).apply {
        marginStart = activity.dp(16)
        bottomMargin = activity.dp(72)
    }

    internal class Actions(
        val onPhotoClicked: (GalleryAsset) -> Unit,
        val onAlbumClicked: (ProtonAlbum) -> Unit,
        val onSelectionChanged: (List<GalleryAsset>) -> Unit,
        val onVisibleThumbnailsChanged: (Set<String>) -> Unit,
        val onRefresh: () -> Unit,
        val onSourceBarLayout: () -> Unit,
        val onAlbumBack: () -> Unit,
        val onSpaceMenu: () -> Unit,
        val onSettings: () -> Unit,
        val onDeleteAllTrash: () -> Unit,
        val onProtonSource: () -> Unit,
        val onAlbumsSource: () -> Unit,
        val onDeviceSource: () -> Unit,
        val onTrashSource: () -> Unit,
        val onDeviceCollection: (DeviceCollection) -> Unit,
        val onDeleteSelection: () -> Unit,
    )

    private data class EmptyPanel(
        val container: LinearLayout,
        val title: TextView,
        val message: TextView,
        val action: Button,
    )

    private data class Header(
        val container: LinearLayout,
        val pageTitle: TextView,
        val status: TextView,
        val settingsButton: ImageButton,
        val refreshButton: ImageButton,
        val albumBackButton: ImageButton,
        val trashDeleteAllButton: Button,
        val empty: EmptyPanel,
    ) {
        val emptyPanel get() = empty.container
        val emptyTitle get() = empty.title
        val emptyMessage get() = empty.message
        val emptyAction get() = empty.action
    }

    private data class SourceBar(
        val container: LinearLayout,
        val proton: Button,
        val albums: Button,
        val device: Button,
        val trash: Button,
    )

    private data class SelectionBar(
        val container: LinearLayout,
        val count: TextView,
        val delete: Button,
    )

}
