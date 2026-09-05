package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.bownee.lenswave.R
import com.bownee.lenswave.UiStyle
import com.bownee.lenswave.dp
import com.bownee.lenswave.proton.ProtonAlbum
import kotlin.math.roundToInt

class GalleryListAdapter(
    private val context: Context,
    private val thumbnailLoader: GalleryThumbnailLoader<Bitmap>,
    /** The tapped photo and its index in the page's flat asset list. */
    private val onPhotoClicked: (GalleryAsset, Int) -> Unit,
    private val onAlbumClicked: (ProtonAlbum) -> Unit,
    private val onLibraryAction: (LibraryAction) -> Unit,
    private val onSelectionChanged: (List<GalleryAsset>) -> Unit,
) : BaseAdapter() {
    private val selected = linkedMapOf<String, GalleryAsset>()
    private var rows: List<GalleryRow> = emptyList()

    /** The photos per row the current rows were chunked with; cells are built to match (see [GallerySpanPolicy]). */
    var photoColumns: Int = GallerySpanPolicy.MIN_COLUMNS
        private set

    /** The rows on show, for mapping a scroll position through its first visible photo. */
    val currentRows: List<GalleryRow> get() = rows
    private var dateLabels: List<String?> = emptyList()
    private var isFastScrolling = false

    /** The list the rows are shown in, captured at the first bind, for touching visible cells in place. */
    private var attachedList: ViewGroup? = null
    private val photoDescription = context.getString(R.string.photo)

    // Row geometry in pixels, resolved once; the density does not change under a running adapter.
    // The photo gap is half of the old 3dp; dp() rounds whole values, so the pixel size is halved instead.
    private val photoGap = (context.dp((PHOTO_GAP_DP * 2).toInt()) / 2).coerceAtLeast(1)
    private val albumGap = context.dp(ALBUM_GAP_DP)
    private val edgePadding = context.dp(EDGE_DP)
    private val albumTextHeight = context.dp(ALBUM_TEXT_HEIGHT_DP)

    // One listener per role, bound once per cell; the cell carries the item it currently shows.
    private val photoClick =
        View.OnClickListener { view ->
            val cell = view as PhotoCell
            val photo = cell.asset ?: return@OnClickListener
            if (selected.isEmpty()) onPhotoClicked(photo, cell.assetIndex) else toggleSelection(photo)
        }
    private val photoLongClick =
        View.OnLongClickListener { view ->
            (view as PhotoCell).asset?.let(::toggleSelection)
            true
        }
    private val albumClick = View.OnClickListener { view -> (view as AlbumCell).album?.let(onAlbumClicked) }
    private val entryClick =
        View.OnClickListener { view ->
            (view as EntryCell).entry?.let { onLibraryAction(it.action) }
        }

    /**
     * Shows finished rows (see [GalleryGrouping]); a selection survives only for photos still
     * listed. Listeners hear about the selection only when it actually shrank, so a publish that
     * merely refreshed the rows does not re-render the header and selection bar.
     */
    fun submitRows(rowSet: GalleryRowSet) {
        rows = rowSet.rows
        dateLabels = rowSet.dateLabels
        photoColumns = rowSet.photoColumns
        val missing = GallerySelectionPolicy.missingSelection(rows, selected.keys)
        if (missing.isNotEmpty()) selected.keys.removeAll(missing)
        notifyDataSetChanged()
        if (missing.isNotEmpty()) notifySelectionChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        forEachVisiblePhotoCell { cell -> if (cell.isSelected) applySelection(cell, false) }
        notifySelectionChanged()
    }

    /**
     * Replaces the selection with the listed photos that the rows still show, e.g. the selection
     * the view model kept while the activity was recreated. Listeners hear about it only when the
     * selection actually changed.
     */
    fun setSelection(stableIds: Set<String>) {
        val next = linkedMapOf<String, GalleryAsset>()
        if (stableIds.isNotEmpty()) {
            for (row in rows) {
                if (row !is GalleryRow.Photos) continue
                for (item in row.items) if (item.stableId in stableIds) next[item.stableId] = item
            }
        }
        if (next.keys == selected.keys) return
        selected.clear()
        selected.putAll(next)
        forEachVisiblePhotoCell { cell -> applySelection(cell, cell.asset?.stableId in selected) }
        notifySelectionChanged()
    }

    /** Forgets every thumbnail on screen; the visible cells reload theirs from the (new) source. */
    fun clearThumbnails() {
        thumbnailLoader.clear()
        rebindVisibleThumbnails(dropShownImages = true)
    }

    fun setFastScrolling(active: Boolean) {
        if (isFastScrolling == active) return
        val wasFastScrolling = isFastScrolling
        isFastScrolling = active
        if (active) thumbnailLoader.cancelPendingLoads()
        if (GalleryFastScrollThumbnailPolicy.shouldRebind(wasFastScrolling, active)) {
            rebindVisibleThumbnails(dropShownImages = false)
        }
    }

    fun hasSelection(): Boolean = selected.isNotEmpty()

    fun selectedPhotos(): List<GalleryAsset> = selected.values.toList()

    /** The date heading the row at [position] falls under; null before the first heading. */
    fun dateLabelForPosition(position: Int): String? = dateLabels.getOrNull(position.coerceAtMost(rows.lastIndex))

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): GalleryRow = rows[position]

    // No stable ids: the list runs in touch mode without a selected item, so after a data change
    // it keeps the first visible position and matches scrap views by position, neither of which
    // needs an id. Ids derived from String.hashCode could collide, which would only mislead that
    // matching, so positions are the simplest correct choice.
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 5

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is GalleryRow.DateHeader -> TYPE_DATE_HEADER
            is GalleryRow.SectionHeading -> TYPE_SECTION_HEADING
            is GalleryRow.Photos -> TYPE_PHOTOS
            is GalleryRow.Albums -> TYPE_ALBUMS
            is GalleryRow.Entries -> TYPE_ENTRIES
        }

    override fun isEnabled(position: Int): Boolean = false

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup?,
    ): View {
        if (parent != null) attachedList = parent
        return when (val row = rows[position]) {
            is GalleryRow.DateHeader -> headerView(row.label, convertView, sizeSp = 15f, heightDp = 46)
            is GalleryRow.SectionHeading -> headerView(row.label, convertView, sizeSp = 17f, heightDp = 60)
            is GalleryRow.Photos -> photosView(row, convertView, parent)
            is GalleryRow.Albums -> albumsView(row, convertView, parent)
            is GalleryRow.Entries -> entriesView(row, convertView)
        }
    }

    private fun headerView(
        text: String,
        convertView: View?,
        sizeSp: Float,
        heightDp: Int,
    ): View {
        val label =
            (convertView as? TextView) ?: TextView(context).apply {
                setTextColor(UiStyle.text)
                textSize = sizeSp
                typeface = UiStyle.medium
                gravity = Gravity.BOTTOM
                setPadding(context.dp(EDGE_DP), 0, context.dp(EDGE_DP), context.dp(10))
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(heightDp))
                ViewCompat.setAccessibilityHeading(this, true)
            }
        label.text = text
        return label
    }

    private fun entriesView(
        row: GalleryRow.Entries,
        convertView: View?,
    ): View {
        val container = (convertView as? LinearLayout) ?: createEntryRow()
        for (column in 0 until ENTRY_COLUMN_COUNT) {
            val cell = container.getChildAt(column) as EntryCell
            val entry = row.items.getOrNull(column)
            cell.entry = entry
            if (entry == null) {
                cell.visibility = View.INVISIBLE
                continue
            }
            cell.visibility = View.VISIBLE
            cell.icon.setImageResource(entry.iconRes)
            cell.label.text = entry.label
            cell.contentDescription = entry.label
        }
        return container
    }

    private fun createEntryRow() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(EDGE_DP), 0, context.dp(EDGE_DP), 0)
            layoutParams =
                AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(ENTRY_HEIGHT_DP + ENTRY_GAP_DP),
                )
            repeat(ENTRY_COLUMN_COUNT) { column ->
                addView(
                    EntryCell(context).apply { setOnClickListener(entryClick) },
                    LinearLayout.LayoutParams(0, context.dp(ENTRY_HEIGHT_DP), 1f).apply {
                        if (column > 0) marginStart = context.dp(ENTRY_GAP_DP / 2)
                        if (column < ENTRY_COLUMN_COUNT - 1) marginEnd = context.dp(ENTRY_GAP_DP / 2)
                    },
                )
            }
        }

    private fun photosView(
        row: GalleryRow.Photos,
        convertView: View?,
        parent: ViewGroup?,
    ): View {
        val columns = photoColumns
        val container =
            (convertView as? FittedRow)?.also { recycled -> resizePhotoRow(recycled, columns) }
                ?: createPhotoRow(columns)
        fitRowHeight(container, parent, ::photoRowHeight)
        for (column in 0 until columns) {
            val cell = container.getChildAt(column) as PhotoCell
            val image = cell.image
            val photo = row.items.getOrNull(column)
            cell.asset = photo
            cell.assetIndex = row.startIndex + column
            if (photo == null) {
                cell.visibility = View.INVISIBLE
                image.tag = null
                thumbnailLoader.forget(cell.thumbnailTarget)
                image.setImageDrawable(null)
                cell.loading.visibility = View.GONE
                cell.videoBadge.visibility = View.GONE
                continue
            }
            cell.visibility = View.VISIBLE
            // Re-binding the photo a cell already shows must not blank it while the cache is re-checked.
            cell.keepsShownImage = image.tag == photo.stableId && image.drawable != null
            image.tag = photo.stableId
            cell.contentDescription =
                if (photo.hasThumbnail) {
                    photo.displayName.ifBlank { photoDescription }
                } else {
                    context.getString(
                        R.string.photo_thumbnail_unavailable,
                        photo.displayName.ifBlank { photoDescription },
                    )
                }
            cell.videoBadge.visibility = if (photo.mediaKind == MediaKind.VIDEO) View.VISIBLE else View.GONE
            applySelection(cell, photo.stableId in selected)
            bindThumbnail(cell, photo)
        }
        return container
    }

    private fun applySelection(
        cell: PhotoCell,
        isSelected: Boolean,
    ) {
        UiStyle.setSelectedState(cell, isSelected)
        cell.isActivated = isSelected
        cell.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        cell.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
        cell.image.scaleX = if (isSelected) 0.9f else 1f
        cell.image.scaleY = if (isSelected) 0.9f else 1f
    }

    /**
     * The recycled row brought to the current span in place: a row from before a span change has
     * the wrong number of cells, so cells are dropped from or added to its end and every cell's
     * gap margins follow the new column count (see [GalleryRowResizePolicy]). The dropped cells
     * are withdrawn from the loader first so a pending decode does not deliver into a view that is
     * never shown again, and the row is left unsized so [fitRowHeight] measures it for the span.
     */
    private fun resizePhotoRow(
        row: FittedRow,
        columns: Int,
    ) {
        val resize = GalleryRowResizePolicy.resize(row.childCount, columns)
        if (resize.isNoOp) return
        if (resize.removeCount > 0) {
            for (column in columns until row.childCount) {
                (row.getChildAt(column) as? PhotoCell)?.let { cell -> thumbnailLoader.forget(cell.thumbnailTarget) }
            }
            row.removeViews(columns, resize.removeCount)
        }
        // The kept cells re-take their margins for the new count; the appended ones arrive with theirs.
        val kept = row.childCount
        for (column in 0 until kept) {
            val cell = row.getChildAt(column)
            cell.layoutParams = photoCellParams(column, columns, cell.layoutParams as LinearLayout.LayoutParams)
        }
        for (column in kept until columns) row.addView(createPhotoCell(), photoCellParams(column, columns))
        row.fittedWidth = -1
    }

    /** The row's height is fitted to the list width at bind (see [fitRowHeight]); it starts unsized. */
    private fun createPhotoRow(columns: Int) =
        FittedRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            repeat(columns) { column -> addView(createPhotoCell(), photoCellParams(column, columns)) }
        }

    private fun createPhotoCell() =
        PhotoCell(context).also { cell ->
            cell.setOnClickListener(photoClick)
            cell.setOnLongClickListener(photoLongClick)
            cell.image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            cell.thumbnailTarget = PhotoTarget(cell)
        }

    /** Equal-weight cell params with the gap split between neighbours; [reuse] keeps an existing instance. */
    private fun photoCellParams(
        column: Int,
        columns: Int,
        reuse: LinearLayout.LayoutParams? = null,
    ): LinearLayout.LayoutParams {
        val gap = photoGap
        val margins = GalleryRowResizePolicy.cellMargins(column, columns, gap)
        return (reuse ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)).apply {
            marginStart = margins.start
            marginEnd = margins.end
            bottomMargin = gap
        }
    }

    /** Cells are squared against the list's own width; the display width only stands in before layout. */
    private fun rowWidth(parent: ViewGroup?): Int {
        val listWidth = parent?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0
        return if (listWidth > 0) listWidth else context.resources.displayMetrics.widthPixels
    }

    private fun albumsView(
        row: GalleryRow.Albums,
        convertView: View?,
        parent: ViewGroup?,
    ): View {
        val container = (convertView as? FittedRow) ?: createAlbumRow(parent)
        fitRowHeight(container, parent, ::albumRowHeight)
        for (column in 0 until ALBUM_COLUMN_COUNT) {
            val cell = container.getChildAt(column) as AlbumCell
            val tile = row.items.getOrNull(column)
            cell.album = tile?.album
            if (tile == null) {
                cell.visibility = View.INVISIBLE
                cell.image.tag = null
                thumbnailLoader.forget(cell.thumbnailTarget)
                cell.image.setImageDrawable(null)
                cell.loading.visibility = View.GONE
                continue
            }
            cell.visibility = View.VISIBLE
            // The texts were resolved once at row build (see GalleryAlbumTile); a bind only assigns them.
            cell.name.text = tile.name
            cell.details.text = tile.details
            cell.contentDescription = tile.contentDescription
            bindAlbumCover(cell, tile.album)
        }
        return container
    }

    /**
     * Rows square their cells against the list width they were built at; the width changes when
     * the list is first laid out (the display width stood in before) or the window resizes, so a
     * recycled row is re-measured against the current width instead of keeping its first height.
     */
    private fun fitRowHeight(
        row: FittedRow,
        parent: ViewGroup?,
        heightFor: (rowWidth: Int) -> Int,
    ) {
        val width = rowWidth(parent)
        if (row.fittedWidth == width) return
        row.fittedWidth = width
        val params = row.layoutParams as AbsListView.LayoutParams
        val height = heightFor(width)
        if (params.height != height) {
            params.height = height
            row.layoutParams = params
        }
    }

    private fun photoRowHeight(rowWidth: Int): Int =
        (rowWidth - photoGap * (photoColumns - 1)) / photoColumns + photoGap

    private fun albumRowHeight(rowWidth: Int): Int {
        val cellWidth = (rowWidth - edgePadding * 2 - albumGap) / ALBUM_COLUMN_COUNT
        return (cellWidth * ALBUM_COVER_ASPECT).roundToInt() + albumTextHeight
    }

    private fun createAlbumRow(parent: ViewGroup?) =
        FittedRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            val gap = albumGap
            setPadding(edgePadding, 0, edgePadding, 0)
            layoutParams =
                AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, albumRowHeight(rowWidth(parent)))
            repeat(ALBUM_COLUMN_COUNT) { column ->
                addView(
                    AlbumCell(context).also { cell ->
                        cell.setOnClickListener(albumClick)
                        cell.thumbnailTarget = AlbumTarget(cell)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = gap / 2
                        if (column < ALBUM_COLUMN_COUNT - 1) marginEnd = gap - gap / 2
                        bottomMargin = gap
                    },
                )
            }
        }

    /** Flips one photo's selection and restyles only its cell; the rest of the grid is untouched. */
    private fun toggleSelection(photo: GalleryAsset) {
        val isSelected = selected.remove(photo.stableId) == null
        if (isSelected) selected[photo.stableId] = photo
        forEachVisiblePhotoCell { cell ->
            if (cell.asset?.stableId == photo.stableId) applySelection(cell, isSelected)
        }
        notifySelectionChanged()
    }

    private fun notifySelectionChanged() {
        onSelectionChanged(selected.values.toList())
    }

    private inline fun forEachVisiblePhotoCell(action: (PhotoCell) -> Unit) {
        val list = attachedList ?: return
        for (rowIndex in 0 until list.childCount) {
            val row = list.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (column in 0 until row.childCount) {
                val cell = row.getChildAt(column) as? PhotoCell ?: continue
                if (cell.asset != null) action(cell)
            }
        }
    }

    private fun rebindVisibleThumbnails(dropShownImages: Boolean) {
        val list = attachedList ?: return
        for (rowIndex in 0 until list.childCount) {
            val row = list.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (column in 0 until row.childCount) {
                when (val cell = row.getChildAt(column)) {
                    is PhotoCell -> {
                        val photo = cell.asset ?: continue
                        if (dropShownImages) cell.image.setImageDrawable(null)
                        cell.keepsShownImage = !dropShownImages && cell.image.drawable != null
                        bindThumbnail(cell, photo)
                    }

                    is AlbumCell -> {
                        val album = cell.album ?: continue
                        if (dropShownImages) cell.image.setImageDrawable(null)
                        bindAlbumCover(cell, album)
                    }
                }
            }
        }
    }

    private fun bindThumbnail(
        cell: PhotoCell,
        photo: GalleryAsset,
    ) {
        thumbnailLoader.load(
            asset = photo,
            allowSourceRead = GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling),
            target = cell.thumbnailTarget,
        )
    }

    private fun bindAlbumCover(
        cell: AlbumCell,
        album: ProtonAlbum,
    ) {
        cell.image.tag = album.nodeUid
        thumbnailLoader.load(
            album = album,
            allowSourceRead = GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling),
            target = cell.thumbnailTarget,
        )
    }

    /** Shows a cell's thumbnail; a delivery for a photo the cell no longer shows is dropped. */
    private inner class PhotoTarget(
        private val cell: PhotoCell,
    ) : GalleryThumbnailTarget<Bitmap> {
        override fun onThumbnail(
            tag: String,
            image: Bitmap?,
        ) {
            val view = cell.image
            if (view.tag != tag) return
            if (image == null && cell.keepsShownImage) {
                cell.loading.visibility = View.GONE
                return
            }
            // A rebind that delivers the bitmap already on screen must not invalidate the cell.
            if (image == null || (view.drawable as? BitmapDrawable)?.bitmap !== image) view.setImageBitmap(image)
            cell.loading.visibility = if (image == null && !isFastScrolling) View.VISIBLE else View.GONE
            view.alpha = if (image == null) 0.45f else 1f
        }
    }

    private inner class AlbumTarget(
        private val cell: AlbumCell,
    ) : GalleryThumbnailTarget<Bitmap> {
        override fun onThumbnail(
            tag: String,
            image: Bitmap?,
        ) {
            val view = cell.image
            val album = cell.album ?: return
            if (view.tag != tag) return
            if (image == null || (view.drawable as? BitmapDrawable)?.bitmap !== image) view.setImageBitmap(image)
            cell.loading.visibility =
                if (image == null && album.coverPhotoNodeUid != null && !isFastScrolling) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            view.alpha = if (image == null) 0.48f else 1f
        }
    }

    internal companion object {
        const val TYPE_DATE_HEADER = 0
        const val TYPE_SECTION_HEADING = 1
        const val TYPE_PHOTOS = 2
        const val TYPE_ALBUMS = 3
        const val TYPE_ENTRIES = 4
        const val ALBUM_COLUMN_COUNT = 2
        const val ENTRY_COLUMN_COUNT = 2
        const val EDGE_DP = 16
        const val ENTRY_HEIGHT_DP = 60
        const val ENTRY_GAP_DP = 10
        const val ALBUM_COVER_ASPECT = 0.82f
        const val PHOTO_GAP_DP = 1.5f
        const val ALBUM_GAP_DP = 12
        const val ALBUM_TEXT_HEIGHT_DP = 58
    }
}

/**
 * A photo or album row that remembers the list width its height was fitted to (see
 * [GalleryListAdapter.fitRowHeight]); an Int field, so a bind compares without boxing.
 */
internal class FittedRow(
    context: Context,
) : LinearLayout(context) {
    var fittedWidth = -1
}
