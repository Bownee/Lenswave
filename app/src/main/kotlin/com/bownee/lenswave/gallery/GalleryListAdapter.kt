package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
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
    private val onSelectionChanged: (List<GalleryAsset>) -> Unit,
    private val currentDestination: () -> GalleryDestination,
) : BaseAdapter() {
    private val selected = linkedMapOf<String, GalleryAsset>()
    private var rows: List<GalleryRow> = emptyList()
    private var isFastScrolling = false

    fun submitPhotos(photos: List<GalleryAsset>) {
        rows = GalleryGrouping.createRows(
            photos = photos,
            unknownDateLabel = context.getString(R.string.unknown_date),
        )
        selected.keys.retainAll(photos.mapTo(mutableSetOf(), GalleryAsset::stableId))
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun submitAlbums(albums: List<ProtonAlbum>) {
        rows = GalleryGrouping.createAlbumRows(albums)
        selected.clear()
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

    fun monthLabelForPosition(position: Int): String? {
        for (index in position.coerceAtMost(rows.lastIndex) downTo 0) {
            (rows[index] as? GalleryRow.MonthHeader)?.let { return it.label }
        }
        return null
    }

    fun isMonthHeader(position: Int): Boolean = rows.getOrNull(position) is GalleryRow.MonthHeader

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): GalleryRow = rows[position]

    override fun getItemId(position: Int): Long = when (val row = rows[position]) {
        is GalleryRow.MonthHeader -> row.key.hashCode().toLong()
        is GalleryRow.Photos -> row.items.first().stableId.hashCode().toLong()
        is GalleryRow.Albums -> row.items.first().nodeUid.hashCode().toLong()
    }

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is GalleryRow.MonthHeader -> TYPE_HEADER
        is GalleryRow.Photos -> TYPE_PHOTOS
        is GalleryRow.Albums -> TYPE_ALBUMS
    }

    override fun isEnabled(position: Int): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
        when (val row = rows[position]) {
            is GalleryRow.MonthHeader -> headerView(row, convertView)
            is GalleryRow.Photos -> photosView(row, convertView)
            is GalleryRow.Albums -> albumsView(row, convertView)
        }

    private fun headerView(row: GalleryRow.MonthHeader, convertView: View?): View {
        val label = (convertView as? TextView) ?: TextView(context).apply {
            setTextColor(UiStyle.text)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(context.dp(12), context.dp(18), context.dp(12), context.dp(8))
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(58))
        }
        label.text = row.label
        ViewCompat.setAccessibilityHeading(label, true)
        return label
    }

    private fun photosView(row: GalleryRow.Photos, convertView: View?): View {
        val container = (convertView as? LinearLayout) ?: createPhotoRow()
        for (column in 0 until COLUMN_COUNT) {
            val cell = container.getChildAt(column) as PhotoCell
            val image = cell.image
            val photo = row.items.getOrNull(column)
            if (photo == null) {
                cell.visibility = View.INVISIBLE
                image.setImageDrawable(null)
                cell.protonBadge.visibility = View.GONE
                cell.setOnClickListener(null)
                cell.setOnLongClickListener(null)
                continue
            }
            cell.visibility = View.VISIBLE
            image.tag = photo.stableId
            val showProtonBadge = PhotoSourceBadgePolicy.shouldShow(currentDestination(), photo)
            val description = photo.displayName.ifBlank { context.getString(R.string.photo) }
            val sourceDescription = if (showProtonBadge) {
                context.getString(R.string.photo_in_proton, description)
            } else {
                description
            }
            cell.contentDescription = if (photo.hasThumbnail) sourceDescription else {
                context.getString(R.string.photo_thumbnail_unavailable, sourceDescription)
            }
            image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            cell.protonBadge.visibility = if (showProtonBadge) View.VISIBLE else View.GONE
            cell.setOnClickListener {
                if (selected.isEmpty()) onPhotoClicked(photo) else toggleSelection(photo)
            }
            cell.setOnLongClickListener {
                toggleSelection(photo)
                true
            }
            val isSelected = photo.stableId in selected
            cell.isSelected = isSelected
            cell.isActivated = isSelected
            ViewCompat.setStateDescription(
                cell,
                context.getString(if (isSelected) R.string.selected else R.string.not_selected),
            )
            cell.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            image.alpha = if (isSelected) 0.58f else 1f
            bindThumbnail(image, photo)
        }
        return container
    }

    private fun createPhotoRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.resources.displayMetrics.widthPixels / COLUMN_COUNT + PHOTO_GAP_PX,
        )
        repeat(COLUMN_COUNT) { column ->
            addView(PhotoCell(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (column > 0) marginStart = PHOTO_GAP_PX / 2
                if (column < COLUMN_COUNT - 1) marginEnd = PHOTO_GAP_PX / 2
                bottomMargin = PHOTO_GAP_PX
            })
        }
    }

    private fun albumsView(row: GalleryRow.Albums, convertView: View?): View {
        val container = (convertView as? LinearLayout) ?: createAlbumRow()
        for (column in 0 until ALBUM_COLUMN_COUNT) {
            val cell = container.getChildAt(column) as AlbumCell
            val album = row.items.getOrNull(column)
            if (album == null) {
                cell.visibility = View.INVISIBLE
                cell.image.setImageDrawable(null)
                cell.setOnClickListener(null)
                continue
            }
            cell.visibility = View.VISIBLE
            val albumName = album.name.ifBlank { context.getString(R.string.untitled_album) }
            val photoCount = context.resources.getQuantityString(
                R.plurals.photo_count,
                album.photoCount.toInt(),
                album.photoCount,
            )
            val details = if (album.isShared) {
                context.getString(R.string.shared_album_details, photoCount)
            } else {
                photoCount
            }
            cell.name.text = albumName
            cell.details.text = details
            cell.contentDescription = context.getString(R.string.album_accessibility, albumName, details)
            cell.setOnClickListener { onAlbumClicked(album) }
            bindAlbumCover(cell.image, album)
        }
        return container
    }

    private fun createAlbumRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        val cellWidth = context.resources.displayMetrics.widthPixels / ALBUM_COLUMN_COUNT
        val rowHeight = (cellWidth * ALBUM_COVER_ASPECT).roundToInt() + context.dp(62)
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
        repeat(ALBUM_COLUMN_COUNT) { column ->
            addView(AlbumCell(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (column > 0) marginStart = ALBUM_GAP_PX / 2
                if (column < ALBUM_COLUMN_COUNT - 1) marginEnd = ALBUM_GAP_PX / 2
                bottomMargin = ALBUM_GAP_PX
            })
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

    private fun bindThumbnail(image: ImageView, photo: GalleryAsset) {
        thumbnailLoader.load(
            asset = photo,
            allowSourceRead = GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling),
        ) { bitmap ->
            if (image.tag != photo.stableId) return@load
            image.setImageBitmap(bitmap)
            if (photo.stableId !in selected) image.alpha = if (bitmap == null) 0.45f else 1f
        }
    }

    private fun bindAlbumCover(image: ImageView, album: ProtonAlbum) {
        val coverNodeUid = album.coverPhotoNodeUid
        val key = coverNodeUid?.let { "album-cover:$it" } ?: "album-empty:${album.nodeUid}"
        image.tag = key
        thumbnailLoader.load(
            album = album,
            allowSourceRead = GalleryFastScrollThumbnailPolicy.shouldReadSource(isFastScrolling),
        ) { bitmap ->
            if (image.tag != key) return@load
            image.setImageBitmap(bitmap)
            image.alpha = if (bitmap == null) 0.48f else 1f
        }
    }

    private class PhotoCell(context: Context) : FrameLayout(context) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(UiStyle.surfaceRaised)
        }
        val check = TextView(context).apply {
            text = "✓"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = UiStyle.rounded(context, UiStyle.accentDark, 16, UiStyle.accent)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val protonBadge = ImageView(context).apply {
            setImageResource(R.drawable.ic_cloud)
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            imageAlpha = 190
            alpha = 0.68f
            background = UiStyle.rounded(
                context,
                Color.argb(165, 12, 14, 19),
                11,
                Color.argb(120, 244, 246, 252),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

        init {
            addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(check, LayoutParams(context.dp(34), context.dp(34), Gravity.TOP or Gravity.END).apply {
                topMargin = context.dp(8)
                marginEnd = context.dp(8)
            })
            addView(protonBadge, LayoutParams(context.dp(22), context.dp(22), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = context.dp(7)
                marginEnd = context.dp(7)
            })
        }
    }

    private class AlbumCell(context: Context) : LinearLayout(context) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(UiStyle.surfaceRaised)
        }
        val name = TextView(context).apply {
            setTextColor(UiStyle.text)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            minHeight = context.dp(34)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM
            setPadding(context.dp(8), 0, context.dp(8), 0)
        }
        val details = TextView(context).apply {
            setTextColor(UiStyle.muted)
            textSize = 12f
            maxLines = 2
            minHeight = context.dp(26)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(8), 0, context.dp(8), 0)
        }

        init {
            orientation = VERTICAL
            isClickable = true
            isFocusable = true
            addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(details, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PHOTOS = 1
        const val TYPE_ALBUMS = 2
        const val COLUMN_COUNT = 3
        const val ALBUM_COLUMN_COUNT = 2
        const val ALBUM_COVER_ASPECT = 0.72f
        const val PHOTO_GAP_PX = 2
        const val ALBUM_GAP_PX = 4
    }
}
