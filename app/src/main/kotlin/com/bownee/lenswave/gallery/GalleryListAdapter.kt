package com.bownee.lenswave.gallery

import android.content.Context
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
    private val thumbnailLoader: GalleryThumbnailLoader,
    private val onPhotoClicked: (GalleryAsset) -> Unit,
    private val onAlbumClicked: (ProtonAlbum) -> Unit,
    private val onLibraryAction: (LibraryAction) -> Unit,
    private val onSelectionChanged: (List<GalleryAsset>) -> Unit,
) : BaseAdapter() {
    private val selected = linkedMapOf<String, GalleryAsset>()
    private var rows: List<GalleryRow> = emptyList()
    private var dateLabels: List<String?> = emptyList()
    private var isFastScrolling = false

    /** Shows finished rows (see [GalleryGrouping]); a selection survives only for photos still listed. */
    fun submitRows(rowSet: GalleryRowSet) {
        rows = rowSet.rows
        dateLabels = rowSet.dateLabels
        if (selected.isNotEmpty()) {
            val stableIds = HashSet<String>()
            rows.forEach { row ->
                if (row is GalleryRow.Photos) row.items.forEach { stableIds.add(it.stableId) }
            }
            selected.keys.retainAll(stableIds)
        }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun clearThumbnails() {
        thumbnailLoader.clear()
        notifyDataSetChanged()
    }

    fun setFastScrolling(active: Boolean) {
        if (isFastScrolling == active) return
        val wasFastScrolling = isFastScrolling
        isFastScrolling = active
        if (active) thumbnailLoader.cancelPendingLoads()
        if (GalleryFastScrollThumbnailPolicy.shouldRebind(wasFastScrolling, active)) {
            notifyDataSetChanged()
        }
    }

    fun selectedPhotos(): List<GalleryAsset> = selected.values.toList()

    /** The date heading the row at [position] falls under; null before the first heading. */
    fun dateLabelForPosition(position: Int): String? = dateLabels.getOrNull(position.coerceAtMost(rows.lastIndex))

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): GalleryRow = rows[position]

    override fun getItemId(position: Int): Long =
        when (val row = rows[position]) {
            is GalleryRow.DateHeader -> {
                row.key.hashCode().toLong()
            }

            is GalleryRow.SectionHeading -> {
                row.key.hashCode().toLong()
            }

            is GalleryRow.Photos -> {
                row.items
                    .first()
                    .stableId
                    .hashCode()
                    .toLong()
            }

            is GalleryRow.Albums -> {
                row.items
                    .first()
                    .nodeUid
                    .hashCode()
                    .toLong()
            }

            is GalleryRow.Entries -> {
                row.items
                    .first()
                    .key
                    .hashCode()
                    .toLong()
            }
        }

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
    ): View =
        when (val row = rows[position]) {
            is GalleryRow.DateHeader -> headerView(row.label, convertView, sizeSp = 15f, heightDp = 46)
            is GalleryRow.SectionHeading -> headerView(row.label, convertView, sizeSp = 17f, heightDp = 60)
            is GalleryRow.Photos -> photosView(row, convertView)
            is GalleryRow.Albums -> albumsView(row, convertView)
            is GalleryRow.Entries -> entriesView(row, convertView)
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
            }
        label.text = text
        ViewCompat.setAccessibilityHeading(label, true)
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
            if (entry == null) {
                cell.visibility = View.INVISIBLE
                cell.setOnClickListener(null)
                continue
            }
            cell.visibility = View.VISIBLE
            cell.icon.setImageResource(entry.iconRes)
            cell.label.text = entry.label
            cell.contentDescription = entry.label
            cell.setOnClickListener { onLibraryAction(entry.action) }
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
                    EntryCell(context),
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
    ): View {
        val container = (convertView as? LinearLayout) ?: createPhotoRow()
        for (column in 0 until COLUMN_COUNT) {
            val cell = container.getChildAt(column) as PhotoCell
            val image = cell.image
            val photo = row.items.getOrNull(column)
            if (photo == null) {
                cell.visibility = View.INVISIBLE
                image.setImageDrawable(null)
                cell.loading.visibility = View.GONE
                cell.videoBadge.visibility = View.GONE
                cell.setOnClickListener(null)
                cell.setOnLongClickListener(null)
                continue
            }
            cell.visibility = View.VISIBLE
            image.tag = photo.stableId
            val description = photo.displayName.ifBlank { context.getString(R.string.photo) }
            cell.contentDescription =
                if (photo.hasThumbnail) {
                    description
                } else {
                    context.getString(R.string.photo_thumbnail_unavailable, description)
                }
            image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            cell.videoBadge.visibility = if (photo.mediaKind == MediaKind.VIDEO) View.VISIBLE else View.GONE
            cell.setOnClickListener {
                if (selected.isEmpty()) onPhotoClicked(photo) else toggleSelection(photo)
            }
            cell.setOnLongClickListener {
                toggleSelection(photo)
                true
            }
            val isSelected = photo.stableId in selected
            UiStyle.setSelectedState(cell, isSelected)
            cell.isActivated = isSelected
            cell.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            cell.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            image.scaleX = if (isSelected) 0.9f else 1f
            image.scaleY = if (isSelected) 0.9f else 1f
            bindThumbnail(cell, photo)
        }
        return container
    }

    private fun createPhotoRow() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Half of the old 3dp gap; dp() rounds whole values, so the pixel size is halved instead.
            val gap = (context.dp((PHOTO_GAP_DP * 2).toInt()) / 2).coerceAtLeast(1)
            layoutParams =
                AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.widthPixels - gap * (COLUMN_COUNT - 1)) / COLUMN_COUNT + gap,
                )
            repeat(COLUMN_COUNT) { column ->
                addView(
                    PhotoCell(context),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = gap / 2
                        if (column < COLUMN_COUNT - 1) marginEnd = gap - gap / 2
                        bottomMargin = gap
                    },
                )
            }
        }

    private fun albumsView(
        row: GalleryRow.Albums,
        convertView: View?,
    ): View {
        val container = (convertView as? LinearLayout) ?: createAlbumRow()
        for (column in 0 until ALBUM_COLUMN_COUNT) {
            val cell = container.getChildAt(column) as AlbumCell
            val album = row.items.getOrNull(column)
            if (album == null) {
                cell.visibility = View.INVISIBLE
                cell.image.setImageDrawable(null)
                cell.loading.visibility = View.GONE
                cell.setOnClickListener(null)
                continue
            }
            cell.visibility = View.VISIBLE
            val albumName = album.name.ifBlank { context.getString(R.string.untitled_album) }
            val photoCount =
                context.resources.getQuantityString(
                    R.plurals.photo_count,
                    album.photoCount.toInt(),
                    album.photoCount,
                )
            val details =
                if (album.isShared) {
                    context.getString(R.string.shared_album_details, photoCount)
                } else {
                    photoCount
                }
            cell.name.text = albumName
            cell.details.text = details
            cell.contentDescription = context.getString(R.string.album_accessibility, albumName, details)
            cell.setOnClickListener { onAlbumClicked(album) }
            bindAlbumCover(cell, album)
        }
        return container
    }

    private fun createAlbumRow() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            val gap = context.dp(ALBUM_GAP_DP)
            setPadding(context.dp(EDGE_DP), 0, context.dp(EDGE_DP), 0)
            val cellWidth =
                (context.resources.displayMetrics.widthPixels - context.dp(EDGE_DP) * 2 - gap) /
                    ALBUM_COLUMN_COUNT
            val rowHeight = (cellWidth * ALBUM_COVER_ASPECT).roundToInt() + context.dp(58)
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
            repeat(ALBUM_COLUMN_COUNT) { column ->
                addView(
                    AlbumCell(context),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = gap / 2
                        if (column < ALBUM_COLUMN_COUNT - 1) marginEnd = gap - gap / 2
                        bottomMargin = gap
                    },
                )
            }
        }

    private fun toggleSelection(photo: GalleryAsset) {
        if (selected.remove(photo.stableId) == null) selected[photo.stableId] = photo
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    private fun notifySelectionChanged() {
        onSelectionChanged(selected.values.toList())
    }

    private fun bindThumbnail(
        cell: PhotoCell,
        photo: GalleryAsset,
    ) {
        val image = cell.image
        thumbnailLoader.load(
            asset = photo,
            allowSourceRead = GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling),
        ) { bitmap ->
            if (image.tag != photo.stableId) return@load
            image.setImageBitmap(bitmap)
            cell.loading.visibility = if (bitmap == null && !isFastScrolling) View.VISIBLE else View.GONE
            image.alpha = if (bitmap == null) 0.45f else 1f
        }
    }

    private fun bindAlbumCover(
        cell: AlbumCell,
        album: ProtonAlbum,
    ) {
        val image = cell.image
        val coverNodeUid = album.coverPhotoNodeUid
        val key = coverNodeUid?.let { "album-cover:$it" } ?: "album-empty:${album.nodeUid}"
        image.tag = key
        thumbnailLoader.load(
            album = album,
            allowSourceRead = GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling),
        ) { bitmap ->
            if (image.tag != key) return@load
            image.setImageBitmap(bitmap)
            cell.loading.visibility =
                if (
                    bitmap == null && coverNodeUid != null && !isFastScrolling
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            image.alpha = if (bitmap == null) 0.48f else 1f
        }
    }

    private companion object {
        const val TYPE_DATE_HEADER = 0
        const val TYPE_SECTION_HEADING = 1
        const val TYPE_PHOTOS = 2
        const val TYPE_ALBUMS = 3
        const val TYPE_ENTRIES = 4
        const val COLUMN_COUNT = 3
        const val ALBUM_COLUMN_COUNT = 2
        const val ENTRY_COLUMN_COUNT = 2
        const val EDGE_DP = 16
        const val ENTRY_HEIGHT_DP = 60
        const val ENTRY_GAP_DP = 10
        const val ALBUM_COVER_ASPECT = 0.82f
        const val PHOTO_GAP_DP = 1.5f
        const val ALBUM_GAP_DP = 12
    }
}
