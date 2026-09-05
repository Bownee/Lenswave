package com.bownee.lenswave.gallery

import android.graphics.Bitmap
import com.bownee.lenswave.proton.ProtonAlbum
import kotlinx.coroutines.CoroutineDispatcher
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
fun interface GalleryThumbnailTarget<Image : Any> {
    fun onThumbnail(
        tag: String,
        image: Image?,
    )
}

/**
 * The decoded thumbnails [GalleryThumbnailLoader] binds. The app's images are bitmaps (see
 * [ProtonThumbnailImages]); the type is open so the loader's bookkeeping can be exercised on the
 * JVM, where a Bitmap cannot be constructed, with any sentinel standing in for one.
 */
interface GalleryThumbnailImages<Image : Any> {
    /** The image only if it is already decoded in memory; never touches disk or suspends. */
    fun peek(
        userId: UserId,
        nodeUid: String,
    ): Image?

    /** Reads or downloads the image; null when there is none. */
    suspend fun load(
        userId: UserId,
        nodeUid: String,
    ): Image?
}

/** The Proton thumbnail store's bitmaps as the loader's images. */
class ProtonThumbnailImages(
    private val source: ProtonThumbnailImageSource,
) : GalleryThumbnailImages<Bitmap> {
    override fun peek(
        userId: UserId,
        nodeUid: String,
    ): Bitmap? = source.peekThumbnail(userId, nodeUid)

    override suspend fun load(
        userId: UserId,
        nodeUid: String,
    ): Bitmap? = source.loadThumbnail(userId, nodeUid)
}

/**
 * Binds decoded thumbnails to gallery cells. Decoded images live only in the Proton thumbnail
 * store's memory cache; this class peeks that cache synchronously so visible cells bind in the same
 * frame, and coalesces the asynchronous loads for everything else.
 *
 * Each target (one per cell) waits on at most one load at a time. Binding a target to another
 * photo withdraws its interest in the previous one, and a load nobody waits on any more is
 * cancelled, so a fling through uncached photos does not leave a decode running for every cell
 * that scrolled past. A load that another visible cell still waits on keeps running.
 *
 * Main thread only: the maps below are touched from bind calls and from the completions, which
 * resume on [scope]'s dispatcher; the reads themselves run on [ioDispatcher].
 */
class GalleryThumbnailLoader<Image : Any>(
    private val scope: CoroutineScope,
    private val images: GalleryThumbnailImages<Image>,
    private val protonUserId: () -> UserId?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val callbacks = mutableMapOf<String, MutableList<Delivery<Image>>>()
    private val loadingJobs = mutableMapOf<String, Job>()

    /** The load key each target currently waits on; a target is present while a load is pending for it. */
    private val interests = mutableMapOf<GalleryThumbnailTarget<Image>, String>()

    fun load(
        asset: GalleryAsset,
        allowSourceRead: Boolean = true,
        target: GalleryThumbnailTarget<Image>,
    ) {
        val tag = asset.stableId
        if (!asset.hasThumbnail) {
            forget(target)
            target.onThumbnail(tag, null)
            return
        }
        val userId = protonUserId()
        userId?.let { images.peek(it, asset.nodeUid) }?.let { cached ->
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
        target: GalleryThumbnailTarget<Image>,
    ) {
        val tag = album.nodeUid
        val coverNodeUid = album.coverPhotoNodeUid
        if (coverNodeUid == null || !album.hasCoverThumbnail) {
            forget(target)
            target.onThumbnail(tag, null)
            return
        }
        val userId = protonUserId()
        userId?.let { images.peek(it, coverNodeUid) }?.let { cached ->
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
    fun forget(target: GalleryThumbnailTarget<Image>) {
        val key = interests.remove(target) ?: return
        val deliveries = callbacks[key] ?: return
        deliveries.removeAll { delivery -> delivery.target === target }
        if (deliveries.isEmpty()) {
            callbacks.remove(key)
            loadingJobs.remove(key)?.cancel()
        }
    }

    /** Drops pending loads; decoded images are owned by the thumbnail store, not this loader. */
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
        target: GalleryThumbnailTarget<Image>,
    ) {
        // Re-binding the photo a cell already waits for must not restart its decode.
        if (interests[target] == key) return
        forget(target)
        interests[target] = key
        callbacks.getOrPut(key, ::mutableListOf) += Delivery(tag, target)
        if (loadingJobs.containsKey(key)) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val image = withContext(ioDispatcher) { runCatching { loadImage(nodeUid) }.getOrNull() }
                // A cancelled load may have been replaced by a fresh one for the same key.
                if (loadingJobs[key] === coroutineContext.job) loadingJobs.remove(key)
                callbacks.remove(key).orEmpty().forEach { delivery ->
                    interests.remove(delivery.target)
                    delivery.target.onThumbnail(delivery.tag, image)
                }
            }
        loadingJobs[key] = job
        job.start()
    }

    private suspend fun loadImage(nodeUid: String): Image? {
        val userId = protonUserId() ?: return null
        return images.load(userId, nodeUid)
    }

    private class Delivery<Image : Any>(
        val tag: String,
        val target: GalleryThumbnailTarget<Image>,
    )
}
