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
    val filterRow: LinearLayout
    val sourceFilterButton: Button
    val mediaFilterButton: Button
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
    val sourceBar: LinearLayout
    val selectionBar: LinearLayout
    private val selectionCount: TextView
    private val selectionDeleteButton: Button
    val settingsButton: ImageButton
    val refreshButton: ImageButton
    val albumBackButton: ImageButton
    val trashDeleteAllButton: Button
    val photosSectionButton: Button
    val albumsSectionButton: Button
    val trashSectionButton: Button

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
        filterRow = header.filterRow
        sourceFilterButton = header.sourceFilterButton
        mediaFilterButton = header.mediaFilterButton
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
            onFavoriteClicked = actions.onFavoriteClicked,
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

        stickyDate = text("", 15f, UiStyle.text).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
            background = UiStyle.rounded(activity, UiStyle.withAlpha(UiStyle.surface, 236), 20)
            elevation = activity.dp(6).toFloat()
            visibility = View.GONE
        }
        root.addView(
            stickyDate,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                activity.dp(40),
                Gravity.TOP or Gravity.START,
            ),
        )

        val source = buildSourceBar()
        sourceBar = source.container
        photosSectionButton = source.photos
        albumsSectionButton = source.albums
        trashSectionButton = source.trash
        root.addView(sourceBar, bottomOverlayParams())

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

        val sourceFilter = filterButton(activity.getString(R.string.filter_all_sources)).apply {
            setOnClickListener { actions.onFilters() }
        }
        val mediaFilter = filterButton(activity.getString(R.string.proton_filter_all)).apply {
            setOnClickListener { actions.onFilters() }
        }
        val filterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                sourceFilter,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)),
            )
            addView(
                mediaFilter,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)).apply {
                    marginStart = activity.dp(8)
                },
            )
        }
        container.addView(filterRow, matchWrap().apply { topMargin = activity.dp(8) })

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
            topMargin = activity.dp(8)
            bottomMargin = activity.dp(8)
        })

        val empty = buildEmptyPanel()
        container.addView(empty.container, matchWrap().apply {
            topMargin = activity.dp(10)
            bottomMargin = activity.dp(12)
        })
        return Header(
            container,
            title,
            filterRow,
            sourceFilter,
            mediaFilter,
            status,
            settings,
            refresh,
            albumBack,
            deleteAll,
            empty,
        )
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
        val photos = sectionButton(activity.getString(R.string.photos)).apply {
            setOnClickListener { actions.onPhotosSection() }
        }
        val albums = sectionButton(activity.getString(R.string.albums)).apply {
            setOnClickListener { actions.onAlbumsSection() }
        }
        val trash = sectionButton(activity.getString(R.string.trash)).apply {
            setOnClickListener { actions.onTrashSection() }
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3))
            background = UiStyle.rounded(
                activity,
                UiStyle.navigationSurface,
                20,
                UiStyle.navigationBorder,
            )
            addView(photos, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
            addView(albums, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply {
                marginStart = activity.dp(3)
            })
            addView(trash, LinearLayout.LayoutParams(0, activity.dp(48), 1f).apply {
                marginStart = activity.dp(3)
            })
        }
        return SourceBar(container, photos, albums, trash)
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
            background = UiStyle.rounded(activity, UiStyle.withAlpha(UiStyle.surface, 250), 22, UiStyle.border)
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

    private fun filterButton(label: String) = Button(activity).apply {
        text = activity.getString(R.string.source_dropdown_label, label)
        textSize = 13f
        setTextColor(UiStyle.text)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(activity.dp(14), 0, activity.dp(14), 0)
        background = UiStyle.rounded(activity, UiStyle.surface, 14, UiStyle.border)
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

    internal class Actions(
        val onPhotoClicked: (GalleryAsset) -> Unit,
        val onFavoriteClicked: (GalleryAsset) -> Unit,
        val onAlbumClicked: (ProtonAlbum) -> Unit,
        val onSelectionChanged: (List<GalleryAsset>) -> Unit,
        val onRefresh: () -> Unit,
        val onAlbumBack: () -> Unit,
        val onFilters: () -> Unit,
        val onSettings: () -> Unit,
        val onDeleteAllTrash: () -> Unit,
        val onPhotosSection: () -> Unit,
        val onAlbumsSection: () -> Unit,
        val onTrashSection: () -> Unit,
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
        val filterRow: LinearLayout,
        val sourceFilterButton: Button,
        val mediaFilterButton: Button,
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
        val photos: Button,
        val albums: Button,
        val trash: Button,
    )

    private data class SelectionBar(
        val container: LinearLayout,
        val count: TextView,
        val delete: Button,
    )

}
