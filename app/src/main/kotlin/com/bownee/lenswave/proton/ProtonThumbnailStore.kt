package com.bownee.lenswave.proton

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Owns the complete thumbnail lifecycle: authenticated storage, one-time decoding, validation,
 * and the process-wide decoded image cache shared by every thumbnail consumer.
 */
@Singleton
internal class ProtonThumbnailStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secureFiles: SecureFileStore,
    private val clock: LenswaveClock,
) {
    private val root = File(context.filesDir, ProtonStorageLayout.METADATA_DIRECTORY).apply { mkdirs() }
    private val bitmaps = object : LruCache<ThumbnailKey, Bitmap>(bitmapCacheSize()) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap): Int = value.byteCount / 1_024
    }
    private val locks = Array(LOCK_COUNT) { Any() }
    /** Stored-thumbnail counts per user; listing a large directory on every progress tick is too slow. */
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun exists(userId: String, nodeUid: String): Boolean = file(userId, nodeUid).let { file ->
        (file.isFile && file.length() > 0L).also { exists ->
            if (!exists) file.delete()
        }
    }

    /** The decoded bitmap when it is already in memory; never touches disk or blocks on a decode. */
    fun peek(userId: String, nodeUid: String): Bitmap? =
        bitmaps.get(ThumbnailKey(userId, nodeUid))?.takeUnless(Bitmap::isRecycled)

    fun load(userId: String, nodeUid: String): Bitmap? {
        val key = ThumbnailKey(userId, nodeUid)
        peek(userId, nodeUid)?.let { return it }
        return synchronized(lock(key)) {
            bitmaps.get(key)?.takeUnless(Bitmap::isRecycled) ?: loadFromDisk(key)
        }
    }

    fun write(userId: String, nodeUid: String, bytes: ByteArray) {
        val key = ThumbnailKey(userId, nodeUid)
        synchronized(lock(key)) {
            val bitmap = ProtonThumbnailCodec.decode(bytes)
                ?: throw IllegalArgumentException("Proton returned an invalid thumbnail image")
            try {
                val target = file(userId, nodeUid)
                val existed = target.isFile && target.length() > 0L
                target.parentFile?.mkdirs()
                secureFiles.write(
                    scope(userId),
                    target,
                    ProtonThumbnailCodec.encode(bitmap),
                    "Could not commit thumbnail cache file",
                )
                target.setLastModified(clock.nowMillis())
                if (!existed) adjustCount(userId, 1)
                bitmaps.put(key, bitmap)
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }
    }

    fun remove(userId: String, nodeUid: String) {
        val key = ThumbnailKey(userId, nodeUid)
        synchronized(lock(key)) {
            bitmaps.remove(key)
            val target = file(userId, nodeUid)
            val existed = target.isFile && target.length() > 0L
            target.delete()
            if (existed) adjustCount(userId, -1)
        }
    }

    fun count(userId: String): Int = counts.getOrPut(userId) {
        directory(userId).listFiles()
            ?.count { file -> file.isFile && file.extension == "thumb" && file.length() > 0L }
            ?: 0
    }

    private fun adjustCount(userId: String, delta: Int) {
        counts.computeIfPresent(userId) { _, count -> (count + delta).coerceAtLeast(0) }
    }

    fun maintain(userId: String) {
        val directory = directory(userId)
        directory.listFiles()?.filter(::isStalePartial)?.forEach(File::delete)
        counts.remove(userId)
        pruneMemoryCache(userId)
    }

    fun removeUnreferenced(userId: String, retainedNodeUids: Collection<String>) {
        val retainedNames = retainedNodeUids.mapTo(mutableSetOf(), AtomicFileStore::safeName)
        directory(userId).listFiles()?.forEach { file ->
            if (isStalePartial(file) || file.extension != "part" && file.nameWithoutExtension !in retainedNames) {
                file.delete()
            }
        }
        counts.remove(userId)
        pruneMemoryCache(userId)
    }

    fun clearMemory(userId: String) {
        counts.remove(userId)
        bitmaps.snapshot().keys.filter { key -> key.userId == userId }.forEach(bitmaps::remove)
    }

    fun retainMemoryFor(userId: String?) {
        bitmaps.snapshot().keys.filter { key -> key.userId != userId }.forEach(bitmaps::remove)
    }

    private fun loadFromDisk(key: ThumbnailKey): Bitmap? {
        val file = file(key.userId, key.nodeUid)
        if (!file.isFile || file.length() <= 0L) {
            file.delete()
            return null
        }
        val bytes = try {
            secureFiles.read(scope(key.userId), file)
        } catch (_: Exception) {
            file.delete()
            return null
        }
        val bitmap = ProtonThumbnailCodec.decode(bytes)
        if (bitmap == null) {
            file.delete()
            return null
        }
        file.setLastModified(clock.nowMillis())
        bitmaps.put(key, bitmap)
        return bitmap
    }

    private fun pruneMemoryCache(userId: String) {
        bitmaps.snapshot().keys
            .filter { key -> key.userId == userId && !exists(key.userId, key.nodeUid) }
            .forEach(bitmaps::remove)
    }

    private fun file(userId: String, nodeUid: String): File =
        File(directory(userId), "${AtomicFileStore.safeName(nodeUid)}.thumb")

    private fun directory(userId: String): File =
        File(File(root, AtomicFileStore.safeName(userId)), ProtonStorageLayout.THUMBNAILS_DIRECTORY)

    private fun scope(userId: String): String = ProtonStorageLayout.mediaScope(userId)

    private fun lock(key: ThumbnailKey): Any = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    private fun isExpired(file: File, ttlMillis: Long): Boolean =
        file.lastModified() <= 0L || clock.nowMillis() - file.lastModified() > ttlMillis

    private fun isStalePartial(file: File): Boolean =
        file.extension == "part" && isExpired(file, ProtonStorageLayout.STALE_PART_TTL_MILLIS)

    /** The only decoded-thumbnail cache in the process, so it must hold a whole gallery screen. */
    private fun bitmapCacheSize(): Int = (Runtime.getRuntime().maxMemory() / 1_024 / 12)
        .coerceIn(8 * 1_024, 48 * 1_024)
        .toInt()

    private data class ThumbnailKey(val userId: String, val nodeUid: String)

    private companion object {
        const val LOCK_COUNT = 32
    }
}

internal object ProtonThumbnailCodec {
    private const val TARGET_LONG_EDGE = 480
    private const val JPEG_QUALITY = 88

    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val longEdge = max(decoded.width, decoded.height)
        if (longEdge <= TARGET_LONG_EDGE) return decoded
        val scale = TARGET_LONG_EDGE.toFloat() / longEdge
        val scaled = decoded.scale(
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            "Could not encode thumbnail image"
        }
        output.toByteArray()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= TARGET_LONG_EDGE) sample *= 2
        return sample
    }
}
