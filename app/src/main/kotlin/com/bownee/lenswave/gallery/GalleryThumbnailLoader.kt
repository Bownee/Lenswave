package com.bownee.lenswave.gallery

import android.graphics.Bitmap
import com.bownee.lenswave.proton.ProtonAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

/**
 * Binds decoded thumbnails to gallery cells. Decoded bitmaps live only in the Proton thumbnail
 * store's memory cache; this class peeks that cache synchronously so visible cells bind in the same
 * frame, and coalesces the asynchronous loads for everything else.
 */
class GalleryThumbnailLoader(
    private val scope: CoroutineScope,
    private val protonRepository: ProtonThumbnailImageSource,
    private val protonUserId: () -> UserId?,
) {
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
            peek = { peekProtonThumbnail(asset.nodeUid) },
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
            peek = { peekProtonThumbnail(requireNotNull(coverNodeUid)) },
            read = { loadProtonThumbnail(requireNotNull(coverNodeUid)) },
            onLoaded = onLoaded,
        )
    }

    /** Drops pending loads; decoded bitmaps are owned by the thumbnail store, not this loader. */
    fun clear() {
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
        peek: () -> Bitmap?,
        read: suspend () -> Bitmap?,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        if (isAvailable) {
            peek()?.let {
                onLoaded(it)
                return
            }
        }
        onLoaded(null)
        if (!isAvailable || !allowSourceRead) return
        callbacks.getOrPut(key, ::mutableListOf) += onLoaded
        if (!loadingKeys.add(key)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val bitmap = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            loadingJobs.remove(key)
            loadingKeys.remove(key)
            callbacks.remove(key).orEmpty().forEach { callback -> callback(bitmap) }
        }
        loadingJobs[key] = job
        job.start()
    }

    private fun peekProtonThumbnail(nodeUid: String): Bitmap? {
        val userId = protonUserId() ?: return null
        return protonRepository.peekThumbnail(userId, nodeUid)
    }

    private suspend fun loadProtonThumbnail(nodeUid: String): Bitmap? {
        val userId = protonUserId() ?: return null
        return protonRepository.loadThumbnail(userId, nodeUid)
    }
}
