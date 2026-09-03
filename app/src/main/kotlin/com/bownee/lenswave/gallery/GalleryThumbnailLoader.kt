package com.bownee.lenswave.gallery

import android.graphics.Bitmap
import android.util.LruCache
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonPhotoGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

class GalleryThumbnailLoader(
    private val scope: CoroutineScope,
    private val protonRepository: ProtonPhotoGateway,
    private val protonUserId: () -> UserId?,
) {
    private val bitmaps = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1_024
    }
    private val loadingKeys = mutableSetOf<String>()
    private val callbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val loadingJobs = mutableMapOf<String, Job>()

    fun load(
        asset: GalleryAsset,
        allowSourceRead: Boolean = true,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        load(
            key = "asset:${asset.stableId}:${protonUserId()?.id}:${asset.hasThumbnail}",
            isAvailable = asset.hasThumbnail,
            allowSourceRead = allowSourceRead,
            read = { loadProtonThumbnail(asset.nodeUid) },
            onLoaded = onLoaded,
        )
    }

    fun load(
        album: ProtonAlbum,
        allowSourceRead: Boolean = true,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        val coverNodeUid = album.coverPhotoNodeUid
        val key = coverNodeUid?.let {
            "album-cover:${protonUserId()?.id}:$it:${album.hasCoverThumbnail}"
        } ?: "album-empty:${protonUserId()?.id}:${album.nodeUid}"
        load(
            key = key,
            isAvailable = coverNodeUid != null && album.hasCoverThumbnail,
            allowSourceRead = allowSourceRead,
            read = {
                loadProtonThumbnail(requireNotNull(coverNodeUid))
            },
            onLoaded = onLoaded,
        )
    }

    fun clear() {
        bitmaps.evictAll()
        cancelPendingLoads()
    }

    fun cancelPendingLoads() {
        loadingJobs.values.forEach(Job::cancel)
        loadingJobs.clear()
        loadingKeys.clear()
        callbacks.clear()
    }

    private fun load(
        key: String,
        isAvailable: Boolean,
        allowSourceRead: Boolean,
        read: suspend () -> Bitmap?,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        bitmaps.get(key)?.let {
            onLoaded(it)
            return
        }
        onLoaded(null)
        if (!isAvailable || !allowSourceRead) return
        callbacks.getOrPut(key, ::mutableListOf) += onLoaded
        if (!loadingKeys.add(key)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val bitmap = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            loadingJobs.remove(key)
            loadingKeys.remove(key)
            if (bitmap != null) bitmaps.put(key, bitmap)
            callbacks.remove(key).orEmpty().forEach { callback -> callback(bitmap) }
        }
        loadingJobs[key] = job
        job.start()
    }

    private suspend fun loadProtonThumbnail(nodeUid: String): Bitmap? {
        val userId = protonUserId() ?: return null
        return protonRepository.loadThumbnail(userId, nodeUid)
    }

    private fun cacheSize(): Int = (Runtime.getRuntime().maxMemory() / 1_024 / 12)
        .coerceIn(8 * 1_024, 48 * 1_024)
        .toInt()
}
