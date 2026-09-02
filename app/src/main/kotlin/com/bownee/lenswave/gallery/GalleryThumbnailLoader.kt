package com.bownee.lenswave.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import androidx.core.net.toUri
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonPhotoGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

class GalleryThumbnailLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val protonRepository: ProtonPhotoGateway,
    private val protonUserId: () -> UserId?,
) {
    private val bitmaps = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1_024
    }
    private val loadingKeys = mutableSetOf<String>()
    private val callbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(asset: GalleryAsset, onLoaded: (Bitmap?) -> Unit) {
        val revision = when (val replica = asset.primaryReplica) {
            is PhotoReplica.Device -> "${replica.modifiedAtEpochMillis}:${replica.sizeBytes}"
            is PhotoReplica.Proton -> "${protonUserId()?.id}:${replica.hasThumbnail}"
        }
        load("asset:${asset.stableId}:$revision", asset.hasThumbnail, { loadAsset(asset) }, onLoaded)
    }

    fun load(album: ProtonAlbum, onLoaded: (Bitmap?) -> Unit) {
        val coverNodeUid = album.coverPhotoNodeUid
        val key = coverNodeUid?.let {
            "album-cover:${protonUserId()?.id}:$it:${album.hasCoverThumbnail}"
        } ?: "album-empty:${protonUserId()?.id}:${album.nodeUid}"
        load(key, coverNodeUid != null && album.hasCoverThumbnail, {
            val userId = protonUserId() ?: return@load null
            protonRepository.readThumbnail(userId, requireNotNull(coverNodeUid))?.decodeThumbnail()
        }, onLoaded)
    }

    fun clear() {
        bitmaps.evictAll()
        loadingKeys.clear()
        callbacks.clear()
    }

    private fun load(
        key: String,
        isAvailable: Boolean,
        read: () -> Bitmap?,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        bitmaps.get(key)?.let {
            onLoaded(it)
            return
        }
        onLoaded(null)
        if (!isAvailable) return
        callbacks.getOrPut(key, ::mutableListOf) += onLoaded
        if (!loadingKeys.add(key)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            loadingKeys.remove(key)
            if (bitmap != null) bitmaps.put(key, bitmap)
            callbacks.remove(key).orEmpty().forEach { callback -> callback(bitmap) }
        }
    }

    private fun loadAsset(asset: GalleryAsset): Bitmap? = when (val replica = asset.primaryReplica) {
        is PhotoReplica.Device -> context.contentResolver.loadThumbnail(
            replica.uri.toUri(),
            Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
            null,
        )
        is PhotoReplica.Proton -> {
            val userId = protonUserId() ?: return null
            protonRepository.readThumbnail(userId, replica.nodeUid)?.decodeThumbnail()
        }
    }

    private fun ByteArray.decodeThumbnail(): Bitmap? = BitmapFactory.decodeByteArray(
        this,
        0,
        size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
    )

    private fun cacheSize(): Int = (Runtime.getRuntime().maxMemory() / 1_024 / 12)
        .coerceIn(8 * 1_024, 48 * 1_024)
        .toInt()

    private companion object {
        const val THUMBNAIL_SIZE = 480
    }
}
