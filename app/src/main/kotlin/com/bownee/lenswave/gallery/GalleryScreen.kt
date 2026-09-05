package com.bownee.lenswave.gallery

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.Insets
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.isNotEmpty
import com.bownee.lenswave.R
import com.bownee.lenswave.UiStyle
import com.bownee.lenswave.dp
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonMediaTag
import kotlinx.coroutines.CoroutineScope
import me.proton.core.domain.entity.UserId

/**
 * The gallery's view tree: a pinned header with the Photos | Albums switch (or a back button and
 * title on sub-pages), the media-type filter chips under it on the Photos tab, the list below,
 * and the floating selection bar.
 */
internal class GalleryScreen(
    private val activity: GalleryActivity,
    scope: CoroutineScope,
    repository: ProtonThumbnailImageSource,
    currentUserId: () -> UserId?,
    private val actions: Actions,
) {
    val root = FrameLayout(activity).apply { setBackgroundColor(UiStyle.background) }
    val list: GalleryListView
    val adapter: GalleryListAdapter
    val selectionBar: LinearLayout
    val settingsButton: ImageButton

    /** Height of the pinned header including the status-bar inset; the list starts below it. */
    val headerHeight: Int get() = stickyHeader.height
    var onHeaderHeightChanged: ((Int) -> Unit)? = null

    private val stickyHeader: LinearLayout
    private val titleRow: LinearLayout
    private val backButton: ImageButton
    private val pageTitle: TextView
    private val tabSwitch: LinearLayout
    private val photosTab: TabLabel
    private val albumsTab: TabLabel
    private val filterRow: HorizontalScrollView
    private val filterChips: Map<GalleryDestination, FilterChip>
    private val galleryHeader: LinearLayout
    private val galleryFooter: View
    private val emptyPanel: LinearLayout
    private val emptyTitle: TextView
    private val emptyMessage: TextView
    private val emptyAction: Button
    private val listingRefusedBanner: LinearLayout

    /** The user closed the banner; it stays closed until the flag clears and is raised again. */
    private var listingRefusedDismissed = false
    private val refreshLayout: GalleryRefreshLayout
    private val stickyDate: TextView
    private val stickyDateController: GalleryStickyDateController
    private val selectionCount: TextView
    private val selectionDeleteButton: Button
    private var safeArea = Insets.NONE
    private var revealedDestination: GalleryDestination? = null

    /**
     * Resolved once rather than on every touch-down. Declared before the init block: a
     * constructor must never read a member below it.
     */
    private val pullGapBelowChips = activity.dp(PULL_GAP_BELOW_CHIPS_DP)

    init {
        adapter =
            GalleryListAdapter(
                context = activity,
                thumbnailLoader =
                    GalleryThumbnailLoader(
                        scope = scope,
                        images = ProtonThumbnailImages(repository),
                        protonUserId = currentUserId,
                    ),
                onPhotoClicked = actions.onPhotoClicked,
                onAlbumClicked = actions.onAlbumClicked,
                onLibraryAction = actions.onLibraryAction,
                onSelectionChanged = actions.onSelectionChanged,
            )

        val header = buildStickyHeader()
        stickyHeader = header.container
        titleRow = header.titleRow
        backButton = header.backButton
        pageTitle = header.pageTitle
        tabSwitch = header.tabSwitch
        photosTab = header.photosTab
        albumsTab = header.albumsTab
        settingsButton = header.settingsButton
        filterRow = header.filterRow
        filterChips = header.filterChips

        val listHeader = buildListHeader()
        galleryHeader = listHeader.container
        // The filter chips scroll away with the content; only the title row stays pinned.
        galleryHeader.addView(filterRow, 0, UiStyle.matchWrap().apply { bottomMargin = activity.dp(6) })
        emptyPanel = listHeader.empty.container
        emptyTitle = listHeader.empty.title
        emptyMessage = listHeader.empty.message
        emptyAction = listHeader.empty.action
        listingRefusedBanner = listHeader.listingRefusedBanner

        galleryFooter =
            View(activity).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            }
        list =
            GalleryListView(activity).apply {
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
        refreshLayout =
            GalleryRefreshLayout(activity).apply {
                setColorSchemeColors(UiStyle.accent)
                setProgressBackgroundColorSchemeColor(UiStyle.surface)
                setOnRefreshListener { actions.onRefresh() }
                pullGate = ::startsPull
                addView(list, UiStyle.matchParentFrame())
            }
        root.addView(refreshLayout, UiStyle.matchParentFrame())

        stickyDate =
            UiStyle.label(activity, sizeSp = 12.5f, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(activity.dp(12), 0, activity.dp(12), 0)
                background =
                    UiStyle.rounded(
                        activity,
                        UiStyle.withAlpha(UiStyle.surfaceRaised, 238),
                        14,
                        UiStyle.border,
                    )
                elevation = activity.dp(4).toFloat()
                visibility = View.GONE
            }
        root.addView(
            stickyDate,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                activity.dp(28),
                Gravity.TOP or Gravity.START,
            ),
        )
        stickyDateController = GalleryStickyDateController(list, adapter, stickyDate)

        root.addView(
            stickyHeader,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        stickyHeader.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) layoutBelowHeader()
        }

        val selection = buildSelectionBar()
        selectionBar = selection.container.apply { visibility = View.GONE }
        selectionCount = selection.count
        selectionDeleteButton = selection.delete
        root.addView(selectionBar, UiStyle.bottomOverlayParams(activity))

        list.setOnFastScrollInteractionListener { active ->
            adapter.setFastScrolling(active)
            if (!active) stickyDateController.schedule()
        }
        stickyDateController.attach()
    }

    fun dispose() {
        stickyDateController.dispose()
    }

    /** The position on screen, with the span and first visible photo a restore into another span maps through. */
    fun captureScrollPosition(): GalleryScrollPosition? {
        val firstVisibleChild = list.getChildAt(0) ?: return null
        val firstVisiblePosition = list.firstVisiblePosition
        return GalleryScrollPosition(
            firstVisiblePosition = firstVisiblePosition,
            topOffset = firstVisibleChild.top,
            photoColumns = adapter.photoColumns,
            firstVisibleAssetIndex =
                GallerySpanPolicy.firstAssetIndexAt(adapter.currentRows, firstVisiblePosition - list.headerViewsCount),
        )
    }

    /**
     * Scrolls to [position], remapped when the rows are now grouped with another span (see
     * [GallerySpanPolicy]). The scroll is posted; [onRestored] runs right before it so the caller
     * can treat the position as pending until the list actually shows it.
     */
    fun restoreScrollPosition(
        position: GalleryScrollPosition,
        onRestored: () -> Unit,
        isCurrentDestination: () -> Boolean,
    ) {
        list.post {
            if (!isCurrentDestination()) return@post
            onRestored()
            val target =
                GallerySpanPolicy.restoredPosition(
                    position,
                    adapter.currentRows,
                    adapter.photoColumns,
                    list.headerViewsCount,
                )
            val maximumPosition = (list.count - 1).coerceAtLeast(0)
            list.setSelectionFromTop(
                target.firstVisiblePosition.coerceIn(0, maximumPosition),
                target.topOffset,
            )
            list.post {
                if (!isCurrentDestination()) return@post
                stickyDateController.schedule()
            }
        }
    }

    /** Sizes the list footer that keeps the last rows clear of the floating selection bar. */
    fun setFooterHeight(height: Int) {
        val params =
            galleryFooter.layoutParams ?: AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
            )
        if (params.height == height) return
        params.height = height
        galleryFooter.layoutParams = params
    }

    /**
     * Sub-pages show a back button and their title; tab roots show the Photos | Albums switch,
     * and the Photos tab adds the filter chips with the current destination highlighted.
     */
    fun renderNavigation(
        destination: GalleryDestination,
        title: String,
    ) {
        val showBack = GalleryNavigationPolicy.showsBack(destination)
        backButton.visibility = if (showBack) View.VISIBLE else View.GONE
        pageTitle.visibility = if (showBack) View.VISIBLE else View.GONE
        tabSwitch.visibility = if (showBack) View.GONE else View.VISIBLE
        if (showBack && title.isNotEmpty()) pageTitle.text = title
        val tab = GalleryNavigationPolicy.tab(destination)
        photosTab.setSelectedTab(tab == GalleryTab.PHOTOS)
        albumsTab.setSelectedTab(tab == GalleryTab.ALBUMS)
        filterRow.visibility = if (GalleryNavigationPolicy.showsFilters(destination)) View.VISIBLE else View.GONE
        filterChips.forEach { (target, chip) -> chip.setSelectedChip(target == destination) }
        // Scrolling the chip into view is a one-off on arrival; later renders must not fight the user's scroll.
        if (revealedDestination != destination) {
            revealedDestination = destination
            filterChips[destination]?.let(::revealChip)
        }
    }

    /** Scrolls back to the top, jumping most of the way first so long lists do not crawl. */
    fun scrollToTop() {
        if (list.firstVisiblePosition > SCROLL_TO_TOP_JUMP_ROWS) list.setSelection(SCROLL_TO_TOP_JUMP_ROWS)
        list.post { list.smoothScrollToPositionFromTop(0, 0) }
    }

    /** Applies the window's safe area to the pinned header and everything laid out under it. */
    fun applySafeArea(insets: Insets) {
        safeArea = insets
        stickyHeader.setPadding(
            activity.dp(16) + insets.left,
            activity.dp(6) + insets.top,
            activity.dp(16) + insets.right,
            activity.dp(8),
        )
        galleryHeader.setPadding(insets.left, activity.dp(2), insets.right, activity.dp(6))
        filterRow.setPadding(activity.dp(16), 0, activity.dp(16), 0)
        layoutBelowHeader()
    }

    fun showContent() {
        emptyPanel.visibility = View.GONE
    }

    /** Mirrors [GalleryUiState.listingRefused]; a dismissed banner stays down until the flag clears and returns. */
    fun renderListingRefused(refused: Boolean) {
        if (!refused) listingRefusedDismissed = false
        val show = refused && !listingRefusedDismissed
        listingRefusedBanner.visibility = if (show) View.VISIBLE else View.GONE
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

    fun renderSelection(selectedCount: Int) {
        val selecting = selectedCount > 0
        selectionCount.text =
            activity.resources.getQuantityString(
                R.plurals.selected_photo_count,
                selectedCount,
                selectedCount,
            )
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
    }

    /** Mirrors the view model's manual refresh state; a finished refresh hides the spinner. */
    fun setRefreshing(refreshing: Boolean) {
        if (refreshLayout.isRefreshing != refreshing) refreshLayout.isRefreshing = refreshing
    }

    /** Only the thumbnail area pulls to refresh; the pinned header, filter chips and a gap below them do not. */
    private fun startsPull(touchY: Float): Boolean {
        val filterRowBounds =
            if (filterRow.isShown) {
                val top = galleryHeader.top + filterRow.top
                top until top + filterRow.height
            } else {
                null
            }
        return GalleryPullToRefreshPolicy.startsPull(
            touchY,
            stickyHeader.height,
            filterRowBounds,
            gapBelowFilterRow = pullGapBelowChips,
        )
    }

    private fun layoutBelowHeader() {
        val headerHeight = stickyHeader.height
        if (list.paddingTop != headerHeight) list.setPadding(0, headerHeight, 0, 0)
        // The spinner drops out from under the pinned header rather than from the screen edge.
        refreshLayout.setProgressViewOffset(false, headerHeight, headerHeight + activity.dp(REFRESH_SPINNER_END_DP))
        (stickyDate.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = headerHeight + activity.dp(8)
            marginStart = activity.dp(8) +
                if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    safeArea.right
                } else {
                    safeArea.left
                }
            stickyDate.layoutParams = this
        }
        onHeaderHeightChanged?.invoke(headerHeight)
    }

    private fun revealChip(chip: FilterChip) {
        filterRow.post {
            val left = chip.left - activity.dp(16)
            val right = chip.right + activity.dp(16)
            if (left < filterRow.scrollX) {
                filterRow.smoothScrollTo(left, 0)
            } else if (right > filterRow.scrollX + filterRow.width) {
                filterRow.smoothScrollTo(right - filterRow.width, 0)
            }
        }
    }

    private fun buildStickyHeader(): StickyHeader {
        val titleRow =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val back =
            UiStyle.iconButton(activity, R.drawable.ic_back, activity.getString(R.string.back)).apply {
                visibility = View.GONE
                setOnClickListener {
                    if (adapter.hasSelection()) adapter.clearSelection() else actions.onBack()
                }
            }
        titleRow.addView(
            back,
            LinearLayout.LayoutParams(activity.dp(TITLE_ROW_HEIGHT_DP), activity.dp(TITLE_ROW_HEIGHT_DP)).apply {
                marginEnd = activity.dp(10)
            },
        )
        val pageTitle =
            UiStyle.label(activity, sizeSp = 20f, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
                ViewCompat.setAccessibilityHeading(this, true)
            }
        titleRow.addView(pageTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val photos =
            TabLabel(activity, activity.getString(R.string.photos)).apply {
                setOnClickListener { actions.onTabSelected(GalleryTab.PHOTOS) }
            }
        val albums =
            TabLabel(activity, activity.getString(R.string.albums)).apply {
                setOnClickListener { actions.onTabSelected(GalleryTab.ALBUMS) }
            }
        val divider = View(activity).apply { setBackgroundColor(UiStyle.border) }
        val tabSwitch =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(4), 0, 0, 0)
                addView(
                    photos,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(TITLE_ROW_HEIGHT_DP)),
                )
                addView(
                    divider,
                    LinearLayout.LayoutParams(activity.dp(1), activity.dp(18)).apply {
                        marginStart = activity.dp(6)
                        marginEnd = activity.dp(6)
                    },
                )
                addView(
                    albums,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(TITLE_ROW_HEIGHT_DP)),
                )
            }
        titleRow.addView(tabSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val settings =
            UiStyle
                .iconButton(
                    activity,
                    R.drawable.ic_settings,
                    activity.getString(R.string.settings),
                    sizeDp = TITLE_ROW_HEIGHT_DP,
                    iconDp = 20,
                ).apply { setOnClickListener { actions.onSettings() } }
        titleRow.addView(
            settings,
            LinearLayout.LayoutParams(activity.dp(TITLE_ROW_HEIGHT_DP), activity.dp(TITLE_ROW_HEIGHT_DP)),
        )

        val chips = linkedMapOf<GalleryDestination, FilterChip>()
        val chipRow =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        // Built once for the row; each chip draws its own copies off the shared constant state.
        val chipBackgrounds =
            FilterChip.Backgrounds(
                selected = UiStyle.rippled(UiStyle.rounded(activity, UiStyle.surfaceRaised, 12), UiStyle.accent),
                plain = UiStyle.rippled(UiStyle.rounded(activity, Color.TRANSPARENT, 12), UiStyle.accent),
            )

        fun addChip(
            destination: GalleryDestination,
            label: String,
            @DrawableRes icon: Int,
        ) {
            val chip =
                FilterChip(activity, label, icon, chipBackgrounds).apply {
                    setOnClickListener { actions.onFilterSelected(destination) }
                }
            chips[destination] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(CHIP_HEIGHT_DP)).apply {
                    if (chipRow.isNotEmpty()) marginStart = activity.dp(8)
                },
            )
        }
        addChip(GalleryDestination.Timeline, activity.getString(R.string.all_photos), R.drawable.ic_photo)
        ProtonMediaTag.entries.forEach { tag ->
            addChip(GalleryDestination.Tag(tag), activity.getString(tag.labelRes), tag.iconRes())
        }
        val filterRow =
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addView(
                    chipRow,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }

        val container =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(UiStyle.withAlpha(UiStyle.background, 244))
                isClickable = true
                addView(titleRow, UiStyle.matchWrap())
            }
        return StickyHeader(container, titleRow, back, pageTitle, tabSwitch, photos, albums, settings, filterRow, chips)
    }

    private fun buildListHeader(): ListHeader {
        val container =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, activity.dp(2), 0, activity.dp(6))
            }
        val banner =
            UiStyle
                .banner(
                    activity,
                    message = activity.getString(R.string.listing_refused_message),
                    actionLabel = activity.getString(R.string.refresh),
                    dismissDescription = activity.getString(R.string.dismiss),
                    onAction = actions.onRefresh,
                    onDismiss = { listingRefusedDismissed = true },
                ).apply { visibility = View.GONE }
        container.addView(
            banner,
            UiStyle.matchWrap().apply {
                marginStart = activity.dp(16)
                marginEnd = activity.dp(16)
                topMargin = activity.dp(4)
                bottomMargin = activity.dp(4)
            },
        )
        val empty = buildEmptyPanel()
        container.addView(
            empty.container,
            UiStyle.matchWrap().apply {
                marginStart = activity.dp(16)
                marginEnd = activity.dp(16)
                topMargin = activity.dp(14)
                bottomMargin = activity.dp(12)
            },
        )
        return ListHeader(container, empty, banner)
    }

    private fun buildEmptyPanel(): EmptyPanel {
        val icon =
            ImageView(activity).apply {
                setImageResource(R.drawable.ic_cloud)
                imageTintList = ColorStateList.valueOf(UiStyle.accent)
                setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
                background = UiStyle.circle(activity, UiStyle.accentSoft)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val title = UiStyle.label(activity, sizeSp = 20f, medium = true).apply { gravity = Gravity.CENTER }
        val message =
            UiStyle.label(activity, sizeSp = 14f, color = UiStyle.muted).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
            }
        val action =
            UiStyle.accentButton(activity, activity.getString(R.string.continue_action)).apply {
                visibility = View.GONE
            }
        val container =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(activity.dp(24), activity.dp(30), activity.dp(24), activity.dp(26))
                background = UiStyle.rounded(activity, UiStyle.surface, 26, UiStyle.border)
                addView(
                    icon,
                    LinearLayout.LayoutParams(activity.dp(64), activity.dp(64)).apply {
                        bottomMargin = activity.dp(16)
                    },
                )
                addView(title, UiStyle.matchWrap())
                addView(message, UiStyle.matchWrap().apply { topMargin = activity.dp(8) })
                addView(
                    action,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(50)).apply {
                        topMargin = activity.dp(20)
                    },
                )
            }
        return EmptyPanel(container, title, message, action)
    }

    private fun buildSelectionBar(): SelectionBar {
        val count =
            UiStyle
                .label(
                    activity,
                    activity.resources.getQuantityString(R.plurals.selected_photo_count, 0, 0),
                    16f,
                    medium = true,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(activity.dp(12), 0, activity.dp(8), 0)
                }
        val delete =
            UiStyle
                .pillButton(activity, activity.getString(R.string.delete), R.drawable.ic_delete, destructive = true)
                .apply { setOnClickListener { actions.onDeleteSelection() } }
        val close =
            UiStyle
                .iconButton(
                    activity,
                    R.drawable.ic_close,
                    activity.getString(R.string.cancel_selection),
                ).apply { setOnClickListener { adapter.clearSelection() } }
        val container =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                background =
                    UiStyle.rounded(
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

    @DrawableRes
    private fun ProtonMediaTag.iconRes(): Int =
        when (this) {
            ProtonMediaTag.FAVORITES -> R.drawable.ic_favorite_border
            ProtonMediaTag.SCREENSHOTS -> R.drawable.ic_screenshot
            ProtonMediaTag.VIDEOS -> R.drawable.ic_play
            ProtonMediaTag.LIVE_PHOTOS -> R.drawable.ic_live
            ProtonMediaTag.MOTION_PHOTOS -> R.drawable.ic_motion
            ProtonMediaTag.SELFIES -> R.drawable.ic_person
            ProtonMediaTag.PORTRAITS -> R.drawable.ic_portrait
            ProtonMediaTag.BURSTS -> R.drawable.ic_burst
            ProtonMediaTag.PANORAMAS -> R.drawable.ic_panorama
            ProtonMediaTag.RAW -> R.drawable.ic_camera
        }

    /** One half of the Photos | Albums switch: a large title that dims when not selected. */
    private class TabLabel(
        context: Context,
        label: String,
    ) : FrameLayout(context) {
        private val labelView =
            UiStyle.label(context, label, 20f, medium = true).apply {
                gravity = Gravity.CENTER
            }

        // Both colours are built once; a render only re-applies them when the tab's state changes.
        // Declared before the init block: it calls setSelectedTab, which reads them.
        private val selectedColor = ColorStateList.valueOf(UiStyle.text)
        private val plainColor = ColorStateList.valueOf(UiStyle.muted)
        private var isSelectedTab: Boolean? = null

        init {
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(context.dp(8), 0, context.dp(8), 0)
            background = UiStyle.rippled(UiStyle.rounded(context, Color.TRANSPARENT, 12), UiStyle.accent)
            addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ViewCompat.setAccessibilityHeading(this, true)
            setSelectedTab(false)
        }

        fun setSelectedTab(selected: Boolean) {
            if (isSelectedTab == selected) return
            isSelectedTab = selected
            UiStyle.setSelectedState(this, selected)
            labelView.setTextColor(if (selected) selectedColor else plainColor)
        }
    }

    /**
     * A media-type filter: icon and label in a pill that fills when selected. The eleven chips
     * are built in the activity's onCreate, so the vector icon is inflated only once the chip
     * joins the window, and the two pill backgrounds are copies off shared constant state rather
     * than four fresh drawables per chip.
     */
    private class FilterChip(
        context: Context,
        label: String,
        @DrawableRes private val icon: Int,
        backgrounds: Backgrounds,
    ) : LinearLayout(context) {
        /** The two looks every chip shares; each chip takes its own drawable off their constant state. */
        class Backgrounds(
            val selected: Drawable,
            val plain: Drawable,
        )

        private val iconView =
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        private val labelView = UiStyle.label(context, label, 14f, medium = true)

        // Both looks are built once; a render only swaps them when the chip's state changes.
        private val selectedBackground = backgrounds.selected.copyForView()
        private val plainBackground = backgrounds.plain.copyForView()
        private val selectedTint = ColorStateList.valueOf(UiStyle.text)
        private val plainTint = ColorStateList.valueOf(UiStyle.muted)
        private var isSelectedChip: Boolean? = null
        private var iconResolved = false

        private fun Drawable.copyForView(): Drawable = constantState?.newDrawable(resources) ?: this

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (iconResolved) return
            iconResolved = true
            iconView.setImageResource(icon)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(context.dp(12), 0, context.dp(14), 0)
            addView(iconView, LayoutParams(context.dp(18), context.dp(18)).apply { marginEnd = context.dp(7) })
            addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setSelectedChip(false)
        }

        fun setSelectedChip(selected: Boolean) {
            if (isSelectedChip == selected) return
            isSelectedChip = selected
            UiStyle.setSelectedState(this, selected)
            val tint = if (selected) selectedTint else plainTint
            iconView.imageTintList = tint
            labelView.setTextColor(tint)
            background = if (selected) selectedBackground else plainBackground
        }
    }

    internal class Actions(
        val onPhotoClicked: (photo: GalleryAsset, index: Int) -> Unit,
        val onAlbumClicked: (ProtonAlbum) -> Unit,
        val onLibraryAction: (LibraryAction) -> Unit,
        val onSelectionChanged: (List<GalleryAsset>) -> Unit,
        val onBack: () -> Unit,
        val onSettings: () -> Unit,
        val onTabSelected: (GalleryTab) -> Unit,
        val onFilterSelected: (GalleryDestination) -> Unit,
        val onDeleteSelection: () -> Unit,
        val onRefresh: () -> Unit,
    )

    private data class EmptyPanel(
        val container: LinearLayout,
        val title: TextView,
        val message: TextView,
        val action: Button,
    )

    private data class StickyHeader(
        val container: LinearLayout,
        val titleRow: LinearLayout,
        val backButton: ImageButton,
        val pageTitle: TextView,
        val tabSwitch: LinearLayout,
        val photosTab: TabLabel,
        val albumsTab: TabLabel,
        val settingsButton: ImageButton,
        val filterRow: HorizontalScrollView,
        val filterChips: Map<GalleryDestination, FilterChip>,
    )

    private data class ListHeader(
        val container: LinearLayout,
        val empty: EmptyPanel,
        val listingRefusedBanner: LinearLayout,
    )

    private data class SelectionBar(
        val container: LinearLayout,
        val count: TextView,
        val delete: Button,
    )

    companion object {
        /** Height of the floating selection bar including padding, used to keep the list clear of it. */
        const val SELECTION_BAR_HEIGHT_DP = 60
        private const val TITLE_ROW_HEIGHT_DP = 40
        private const val CHIP_HEIGHT_DP = 38
        private const val REFRESH_SPINNER_END_DP = 56

        /** Pulls that begin this close under the filter chips do not refresh. */
        private const val PULL_GAP_BELOW_CHIPS_DP = 18
        private const val SCROLL_TO_TOP_JUMP_ROWS = 12
    }
}
