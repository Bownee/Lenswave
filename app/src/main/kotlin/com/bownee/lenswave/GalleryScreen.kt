package com.bownee.lenswave

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryListAdapter
import com.bownee.lenswave.gallery.GalleryListView
import com.bownee.lenswave.gallery.GalleryScrollPosition
import com.bownee.lenswave.gallery.GalleryTab
import com.bownee.lenswave.gallery.GalleryThumbnailLoader
import com.bownee.lenswave.gallery.LibraryAction
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonPhotoGateway
import kotlinx.coroutines.CoroutineScope
import me.proton.core.domain.entity.UserId

internal class GalleryScreen(
    private val activity: GalleryActivity,
    scope: CoroutineScope,
    repository: ProtonPhotoGateway,
    currentUserId: () -> UserId?,
    private val actions: Actions,
) {
    val root = FrameLayout(activity).apply { setBackgroundColor(UiStyle.background) }
    private val pageTitle: TextView
    private val status: TextView
    val list: GalleryListView
    val galleryHeader: LinearLayout
    val galleryFooter: View
    private val emptyPanel: LinearLayout
    private val emptyTitle: TextView
    private val emptyMessage: TextView
    private val emptyAction: Button
    val adapter: GalleryListAdapter
    val stickyDate: TextView
    val tabBar: LinearLayout
    val selectionBar: LinearLayout
    private val selectionCount: TextView
    private val selectionDeleteButton: Button
    val settingsButton: ImageButton
    private val backButton: ImageButton
    val trashDeleteAllButton: Button
    private val photosTab: TabButton
    private val libraryTab: TabButton

    private var pendingStickyDatePosition: Int? = null
    private var stickyDateUpdatePosted = false
    private val stickyDateUpdate = Runnable {
        stickyDateUpdatePosted = false
        val position = pendingStickyDatePosition ?: return@Runnable
        pendingStickyDatePosition = null
        updateStickyDate(position)
    }
    init {
        val header = buildGalleryHeader()
        galleryHeader = header.container
        pageTitle = header.pageTitle
        status = header.status
        settingsButton = header.settingsButton
        backButton = header.backButton
        trashDeleteAllButton = header.trashDeleteAllButton
        emptyPanel = header.emptyPanel
        emptyTitle = header.emptyTitle
        emptyMessage = header.emptyMessage
        emptyAction = header.emptyAction

        adapter = GalleryListAdapter(
            context = activity,
            thumbnailLoader = GalleryThumbnailLoader(
                scope = scope,
                protonRepository = repository,
                protonUserId = currentUserId,
            ),
            onPhotoClicked = actions.onPhotoClicked,
            onFavoriteClicked = actions.onFavoriteClicked,
            onAlbumClicked = actions.onAlbumClicked,
            onLibraryAction = actions.onLibraryAction,
            onSelectionChanged = actions.onSelectionChanged,
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

        stickyDate = text("", 14f, UiStyle.text).apply {
            gravity = Gravity.CENTER
            typeface = UiStyle.medium
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
            background = UiStyle.rounded(
                activity,
                UiStyle.withAlpha(UiStyle.surfaceRaised, 238),
                18,
                UiStyle.border,
            )
            elevation = activity.dp(4).toFloat()
            visibility = View.GONE
        }
        root.addView(
            stickyDate,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                activity.dp(36),
                Gravity.TOP or Gravity.START,
            ),
        )

        val tabs = buildTabBar()
        tabBar = tabs.container
        photosTab = tabs.photos
        libraryTab = tabs.library
        root.addView(tabBar, bottomOverlayParams())

        val selection = buildSelectionBar()
        selectionBar = selection.container.apply { visibility = View.GONE }
        selectionCount = selection.count
        selectionDeleteButton = selection.delete
        root.addView(selectionBar, bottomOverlayParams())

        attachScrollListeners()
    }

    fun dispose() {
        list.removeCallbacks(stickyDateUpdate)
        pendingStickyDatePosition = null
        stickyDateUpdatePosted = false
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
            list.post {
                if (!isCurrentDestination()) return@post
                scheduleStickyDateUpdate(list.firstVisiblePosition)
            }
        }
    }

    fun renderHeader(statusText: String, showDeleteAll: Boolean, refreshing: Boolean) {
        status.text = statusText
        trashDeleteAllButton.visibility = if (showDeleteAll) View.VISIBLE else View.GONE
        trashDeleteAllButton.isEnabled = !refreshing
        trashDeleteAllButton.alpha = if (refreshing) 0.5f else 1f
    }

    fun renderNavigation(title: String, tab: GalleryTab, showBack: Boolean) {
        if (title.isNotEmpty()) pageTitle.text = title
        backButton.visibility = if (showBack) View.VISIBLE else View.GONE
        photosTab.setSelectedTab(tab == GalleryTab.PHOTOS)
        libraryTab.setSelectedTab(tab == GalleryTab.LIBRARY)
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

    fun updateStickyDateMargins(top: Int, start: Int) {
        (stickyDate.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = top
            marginStart = start
            stickyDate.layoutParams = this
        }
    }

    private fun attachScrollListeners() {
        list.setOnFastScrollInteractionListener { active ->
            adapter.setFastScrolling(active)
            if (!active) {
                scheduleStickyDateUpdate(list.firstVisiblePosition)
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
                scheduleStickyDateUpdate(firstVisibleItem)
            }
        })
    }

    private fun scheduleStickyDateUpdate(firstVisibleItem: Int) {
        pendingStickyDatePosition = firstVisibleItem
        if (stickyDateUpdatePosted) return
        stickyDateUpdatePosted = true
        list.postOnAnimation(stickyDateUpdate)
    }

    private fun updateStickyDate(firstVisibleItem: Int) {
        if (firstVisibleItem < list.headerViewsCount) {
            stickyDate.visibility = View.GONE
            return
        }
        val position = firstVisibleItem - list.headerViewsCount
        val label = adapter.dateLabelForPosition(position)
        if (label == null) {
            stickyDate.visibility = View.GONE
        } else {
            stickyDate.text = label
            stickyDate.visibility = View.VISIBLE
        }
    }

    private fun buildGalleryHeader(): Header {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(6))
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = UiStyle.iconButton(activity, R.drawable.ic_back, activity.getString(R.string.back)).apply {
            visibility = View.GONE
            setOnClickListener {
                if (adapter.selectedPhotos().isNotEmpty()) adapter.clearSelection() else actions.onBack()
            }
        }
        titleRow.addView(back, LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)).apply {
            marginEnd = activity.dp(10)
        })
        val title = text(activity.getString(R.string.photos), 28f, UiStyle.text).apply {
            typeface = UiStyle.medium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, true)
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = UiStyle.iconButton(
            activity,
            R.drawable.ic_settings,
            activity.getString(R.string.settings),
        ).apply { setOnClickListener { actions.onSettings() } }
        titleRow.addView(settings, LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)))
        container.addView(titleRow, matchWrap())

        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(28)
        }
        val status = text("", 13.5f, UiStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val deleteAll = pillButton(
            activity.getString(R.string.delete_all),
            R.drawable.ic_delete,
            destructive = true,
        ).apply {
            visibility = View.GONE
            setOnClickListener { actions.onDeleteAllTrash() }
        }
        statusRow.addView(
            deleteAll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
                marginEnd = activity.dp(16)
            },
        )
        container.addView(statusRow, matchWrap().apply {
            topMargin = activity.dp(2)
            bottomMargin = activity.dp(4)
        })

        val empty = buildEmptyPanel()
        container.addView(empty.container, matchWrap().apply {
            topMargin = activity.dp(14)
            bottomMargin = activity.dp(12)
        })
        return Header(
            container,
            title,
            status,
            settings,
            back,
            deleteAll,
            empty,
        )
    }

    private fun buildEmptyPanel(): EmptyPanel {
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_cloud)
            imageTintList = ColorStateList.valueOf(UiStyle.accent)
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
            background = UiStyle.circle(activity, UiStyle.accentSoft)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = text("", 20f, UiStyle.text).apply {
            gravity = Gravity.CENTER
            typeface = UiStyle.medium
        }
        val message = text("", 14f, UiStyle.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        val action = accentButton(activity.getString(R.string.continue_action)).apply { visibility = View.GONE }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(activity.dp(24), activity.dp(30), activity.dp(24), activity.dp(26))
            background = UiStyle.rounded(activity, UiStyle.surface, 26, UiStyle.border)
            addView(icon, LinearLayout.LayoutParams(activity.dp(64), activity.dp(64)).apply {
                bottomMargin = activity.dp(16)
            })
            addView(title, matchWrap())
            addView(message, matchWrap().apply { topMargin = activity.dp(8) })
            addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(50)).apply {
                topMargin = activity.dp(20)
            })
        }
        return EmptyPanel(container, title, message, action)
    }

    private fun buildTabBar(): TabBar {
        val photos = TabButton(activity, R.drawable.ic_photo, activity.getString(R.string.photos)).apply {
            setOnClickListener { actions.onPhotosTab() }
        }
        val library = TabButton(activity, R.drawable.ic_library, activity.getString(R.string.library)).apply {
            setOnClickListener { actions.onLibraryTab() }
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6))
            background = UiStyle.rounded(
                activity,
                UiStyle.withAlpha(UiStyle.surface, 242),
                30,
                UiStyle.border,
            )
            elevation = activity.dp(10).toFloat()
            addView(photos, LinearLayout.LayoutParams(0, activity.dp(52), 1f))
            addView(library, LinearLayout.LayoutParams(0, activity.dp(52), 1f).apply {
                marginStart = activity.dp(6)
            })
        }
        return TabBar(container, photos, library)
    }

    private fun buildSelectionBar(): SelectionBar {
        val count = text(activity.resources.getQuantityString(R.plurals.selected_photo_count, 0, 0), 16f, UiStyle.text).apply {
            typeface = UiStyle.medium
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(12), 0, activity.dp(8), 0)
        }
        val delete = pillButton(activity.getString(R.string.delete), R.drawable.ic_delete, destructive = true).apply {
            setOnClickListener { actions.onDeleteSelection() }
        }
        val close = UiStyle.iconButton(
            activity,
            R.drawable.ic_close,
            activity.getString(R.string.cancel_selection),
        ).apply { setOnClickListener { adapter.clearSelection() } }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            background = UiStyle.rounded(
                activity,
                UiStyle.withAlpha(UiStyle.surface, 246),
                30,
                UiStyle.border,
            )
            elevation = activity.dp(10).toFloat()
            addView(close, LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)))
            addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(delete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44)))
        }
        return SelectionBar(container, count, delete)
    }

    private fun pillButton(label: String, icon: Int, destructive: Boolean = false) =
        Button(activity).apply {
            text = label
            textSize = 14f
            typeface = UiStyle.medium
            val tint = if (destructive) UiStyle.danger else UiStyle.text
            setTextColor(tint)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(tint))
            compoundDrawablePadding = activity.dp(6)
            setPadding(activity.dp(14), 0, activity.dp(16), 0)
            background = UiStyle.rippled(
                UiStyle.rounded(activity, if (destructive) UiStyle.dangerSoft else UiStyle.surfaceRaised, 22),
                tint,
            )
        }

    private fun accentButton(label: String) = Button(activity).apply {
        text = label
        textSize = 15f
        typeface = UiStyle.medium
        setTextColor(UiStyle.onAccent)
        isAllCaps = false
        background = UiStyle.rippled(UiStyle.rounded(activity, UiStyle.accent, 25), UiStyle.onAccent)
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

    /** A tab in the floating bottom bar: icon and label, tinted by selection state. */
    private class TabButton(
        context: android.content.Context,
        icon: Int,
        label: String,
    ) : LinearLayout(context) {
        private val iconView = ImageView(context).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val labelView = TextView(context).apply {
            text = label
            textSize = 14f
            typeface = UiStyle.medium
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(iconView, LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(8) })
            addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setSelectedTab(false)
        }

        fun setSelectedTab(selected: Boolean) {
            isSelected = selected
            ViewCompat.setStateDescription(
                this,
                context.getString(if (selected) R.string.selected else R.string.not_selected),
            )
            val tint = if (selected) UiStyle.accent else UiStyle.muted
            iconView.imageTintList = ColorStateList.valueOf(tint)
            labelView.setTextColor(if (selected) UiStyle.text else UiStyle.muted)
            background = UiStyle.rippled(
                UiStyle.rounded(context, if (selected) UiStyle.accentSoft else Color.TRANSPARENT, 24),
                UiStyle.accent,
            )
        }
    }

    internal class Actions(
        val onPhotoClicked: (GalleryAsset) -> Unit,
        val onFavoriteClicked: (GalleryAsset) -> Unit,
        val onAlbumClicked: (ProtonAlbum) -> Unit,
        val onLibraryAction: (LibraryAction) -> Unit,
        val onSelectionChanged: (List<GalleryAsset>) -> Unit,
        val onBack: () -> Unit,
        val onSettings: () -> Unit,
        val onDeleteAllTrash: () -> Unit,
        val onPhotosTab: () -> Unit,
        val onLibraryTab: () -> Unit,
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
        val backButton: ImageButton,
        val trashDeleteAllButton: Button,
        val empty: EmptyPanel,
    ) {
        val emptyPanel get() = empty.container
        val emptyTitle get() = empty.title
        val emptyMessage get() = empty.message
        val emptyAction get() = empty.action
    }

    private data class TabBar(
        val container: LinearLayout,
        val photos: TabButton,
        val library: TabButton,
    )

    private data class SelectionBar(
        val container: LinearLayout,
        val count: TextView,
        val delete: Button,
    )

}
