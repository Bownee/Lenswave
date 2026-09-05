package com.bownee.lenswave.gallery

import android.graphics.Bitmap
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonThumbnailImageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
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
 *
 * Each target (one per cell) waits on at most one load at a time. Binding a target to another
 * photo withdraws its interest in the previous one, and a load nobody waits on any more is
 * cancelled, so a fling through uncached photos does not leave a decode running for every cell
 * that scrolled past. A load that another visible cell still waits on keeps running.
 *
 * Main thread only: the maps below are touched from bind calls and from the completions, which
 * resume on [scope]'s dispatcher.
 */
class GalleryThumbnailLoader(
    private val scope: CoroutineScope,
    private val protonRepository: ProtonThumbnailImageSource,
    private val protonUserId: () -> UserId?,
) {
    private val callbacks = mutableMapOf<String, MutableList<Delivery>>()
    private val loadingJobs = mutableMapOf<String, Job>()

    /** The load key each target currently waits on; a target is present while a load is pending for it. */
    private val interests = mutableMapOf<GalleryThumbnailTarget, String>()

    fun load(
        asset: GalleryAsset,
        allowSourceRead: Boolean = true,
        target: GalleryThumbnailTarget,
    ) {
        val tag = asset.stableId
        if (!asset.hasThumbnail) {
            forget(target)
            target.onThumbnail(tag, null)
            return
        }
        val userId = protonUserId()
        userId?.let { protonRepository.peekThumbnail(it, asset.nodeUid) }?.let { cached ->
            forget(target)
            target.onThumbnail(tag, cached)
            return
        }
        target.onThumbnail(tag, null)
        if (userId == null || !allowSourceRead) {
            forget(target)
            return
        }
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
            forget(target)
            target.onThumbnail(tag, null)
            return
        }
        val userId = protonUserId()
        userId?.let { protonRepository.peekThumbnail(it, coverNodeUid) }?.let { cached ->
            forget(target)
            target.onThumbnail(tag, cached)
            return
        }
        target.onThumbnail(tag, null)
        if (userId == null || !allowSourceRead) {
            forget(target)
            return
        }
        enqueue("album-cover:${userId.id}:$coverNodeUid", tag, coverNodeUid, target)
    }

    /**
     * Withdraws [target]'s interest in whatever it was waiting for, for a cell that now shows
     * nothing. The load itself is cancelled once no other target waits on it.
     */
    fun forget(target: GalleryThumbnailTarget) {
        val key = interests.remove(target) ?: return
        val deliveries = callbacks[key] ?: return
        deliveries.removeAll { delivery -> delivery.target === target }
        if (deliveries.isEmpty()) {
            callbacks.remove(key)
            loadingJobs.remove(key)?.cancel()
        }
    }

    /** Drops pending loads; decoded bitmaps are owned by the thumbnail store, not this loader. */
    fun clear() {
        cancelPendingLoads()
    }

    fun cancelPendingLoads() {
        loadingJobs.values.forEach(Job::cancel)
        loadingJobs.clear()
        callbacks.clear()
        interests.clear()
    }

    private fun enqueue(
        key: String,
        tag: String,
        nodeUid: String,
        target: GalleryThumbnailTarget,
    ) {
        // Re-binding the photo a cell already waits for must not restart its decode.
        if (interests[target] == key) return
        forget(target)
        interests[target] = key
        callbacks.getOrPut(key, ::mutableListOf) += Delivery(tag, target)
        if (loadingJobs.containsKey(key)) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val bitmap = withContext(Dispatchers.IO) { runCatching { loadProtonThumbnail(nodeUid) }.getOrNull() }
                // A cancelled load may have been replaced by a fresh one for the same key.
                if (loadingJobs[key] === coroutineContext.job) loadingJobs.remove(key)
                callbacks.remove(key).orEmpty().forEach { delivery ->
                    interests.remove(delivery.target)
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
