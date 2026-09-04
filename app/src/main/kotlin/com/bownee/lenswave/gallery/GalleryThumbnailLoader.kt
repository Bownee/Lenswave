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
 * Receives the thumbnail requested for [tag] (a photo's stable id or an album's node uid). A
 * recycled cell compares the tag with the one it currently shows and drops stale deliveries.
 */
fun interface GalleryThumbnailTarget {
    fun onThumbnail(
        tag: String,
        bitmap: Bitmap?,
    )
}

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
    private val callbacks = mutableMapOf<String, MutableList<Delivery>>()
    private val loadingJobs = mutableMapOf<String, Job>()

    fun load(
        asset: GalleryAsset,
        allowSourceRead: Boolean = true,
        target: GalleryThumbnailTarget,
    ) {
        val tag = asset.stableId
        if (!asset.hasThumbnail) {
            target.onThumbnail(tag, null)
            return
        }
        val userId = protonUserId()
        userId?.let { protonRepository.peekThumbnail(it, asset.nodeUid) }?.let { cached ->
            target.onThumbnail(tag, cached)
            return
        }
        target.onThumbnail(tag, null)
        if (userId == null || !allowSourceRead) return
        // The dedup key is only built for the asynchronous path; cache hits never allocate it.
        enqueue("asset:$tag:${userId.id}", tag, asset.nodeUid, target)
    }

    fun load(
        album: ProtonAlbum,
        allowSourceRead: Boolean = true,
        target: GalleryThumbnailTarget,
    ) {
        val tag = album.nodeUid
        val coverNodeUid = album.coverPhotoNodeUid
        if (coverNodeUid == null || !album.hasCoverThumbnail) {
            target.onThumbnail(tag, null)
            return
        }
        val userId = protonUserId()
        userId?.let { protonRepository.peekThumbnail(it, coverNodeUid) }?.let { cached ->
            target.onThumbnail(tag, cached)
            return
        }
        target.onThumbnail(tag, null)
        if (userId == null || !allowSourceRead) return
        enqueue("album-cover:${userId.id}:$coverNodeUid", tag, coverNodeUid, target)
    }

    /** Drops pending loads; decoded bitmaps are owned by the thumbnail store, not this loader. */
    fun clear() {
        cancelPendingLoads()
    }

    fun cancelPendingLoads() {
        loadingJobs.values.forEach(Job::cancel)
        loadingJobs.clear()
        callbacks.clear()
    }

    private fun enqueue(
        key: String,
        tag: String,
        nodeUid: String,
        target: GalleryThumbnailTarget,
    ) {
        callbacks.getOrPut(key, ::mutableListOf) += Delivery(tag, target)
        if (loadingJobs.containsKey(key)) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val bitmap = withContext(Dispatchers.IO) { runCatching { loadProtonThumbnail(nodeUid) }.getOrNull() }
                loadingJobs.remove(key)
                callbacks.remove(key).orEmpty().forEach { delivery ->
                    delivery.target.onThumbnail(delivery.tag, bitmap)
                }
            }
        loadingJobs[key] = job
        job.start()
    }

    private suspend fun loadProtonThumbnail(nodeUid: String): Bitmap? {
        val userId = protonUserId() ?: return null
        return protonRepository.loadThumbnail(userId, nodeUid)
    }

    private class Delivery(
        val tag: String,
        val target: GalleryThumbnailTarget,
    )
}
