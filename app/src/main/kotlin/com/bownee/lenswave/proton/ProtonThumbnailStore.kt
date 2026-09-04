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
import kotlinx.coroutines.CancellationException
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
internal class ProtonThumbnailStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val secureFiles: SecureFileStore,
        private val clock: LenswaveClock,
    ) {
        private val root = File(context.filesDir, ProtonStorageLayout.METADATA_DIRECTORY).apply { mkdirs() }
        private val bitmaps =
            object : LruCache<ThumbnailKey, Bitmap>(bitmapCacheSize()) {
                override fun sizeOf(
                    key: ThumbnailKey,
                    value: Bitmap,
                ): Int = value.byteCount / 1_024
            }
        private val locks = Array(LOCK_COUNT) { Any() }

        /** Stored-thumbnail counts per user; listing a large directory on every progress tick is too slow. */
        private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun exists(
            userId: String,
            nodeUid: String,
        ): Boolean = file(userId, nodeUid).let { file -> file.isFile && file.length() > 0L }

        /**
         * File names (without extension) of every stored thumbnail, from a single directory
         * listing. Writes are atomic renames, so a listed name is a complete file.
         */
        fun storedNames(userId: String): Set<String> =
            directory(userId)
                .list()
                ?.mapNotNullTo(HashSet()) { name -> name.removeSuffix(".thumb").takeIf { it != name } }
                .orEmpty()

        /** The decoded bitmap when it is already in memory; never touches disk or blocks on a decode. */
        fun peek(
            userId: String,
            nodeUid: String,
        ): Bitmap? = bitmaps.get(ThumbnailKey(userId, nodeUid))?.takeUnless(Bitmap::isRecycled)

        /**
         * The decoded thumbnail, from memory or disk. [isActive] is consulted before the decrypt
         * and again before the decode; when it turns false the load throws [CancellationException]
         * instead of spending the decode on a cell that has scrolled away. Callers outside a
         * coroutine leave it at its default.
         */
        fun load(
            userId: String,
            nodeUid: String,
            isActive: () -> Boolean = { true },
        ): Bitmap? {
            val key = ThumbnailKey(userId, nodeUid)
            peek(userId, nodeUid)?.let { return it }
            return synchronized(lock(key)) {
                bitmaps.get(key)?.takeUnless(Bitmap::isRecycled) ?: loadFromDisk(key, isActive)
            }
        }

        /**
         * A complete pixel decode is the validation: a thumbnail that only carries a header would
         * otherwise be stored, fail in the grid, be queued again and downloaded again forever
         * (see the instrumented test on truncated thumbnails). The decode happens outside the
         * shard lock, and the bytes are stored as delivered unless they need downsampling, so a
         * normal 480 px thumbnail is never re-encoded; the lock covers only the commit and the
         * cache put.
         */
        fun write(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            val decoded =
                ProtonThumbnailCodec.decodeForStore(bytes)
                    ?: throw IllegalArgumentException("Proton returned an invalid thumbnail image")
            val bitmap = decoded.bitmap
            try {
                val stored = if (decoded.downsampled) ProtonThumbnailCodec.encode(bitmap) else bytes
                val key = ThumbnailKey(userId, nodeUid)
                synchronized(lock(key)) {
                    val target = file(userId, nodeUid)
                    val existed = target.isFile && target.length() > 0L
                    target.parentFile?.mkdirs()
                    secureFiles.write(scope(userId), target, stored, "Could not commit thumbnail cache file")
                    target.setLastModified(clock.nowMillis())
                    if (!existed) adjustCount(userId, 1)
                    bitmaps.put(key, bitmap)
                }
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

        fun remove(
            userId: String,
            nodeUid: String,
        ) {
            val key = ThumbnailKey(userId, nodeUid)
            synchronized(lock(key)) {
                bitmaps.remove(key)
                val target = file(userId, nodeUid)
                val existed = target.isFile && target.length() > 0L
                target.delete()
                if (existed) adjustCount(userId, -1)
            }
        }

        fun count(userId: String): Int =
            counts.getOrPut(userId) {
                directory(userId)
                    .listFiles()
                    ?.count { file -> file.isFile && file.extension == "thumb" && file.length() > 0L }
                    ?: 0
            }

        private fun adjustCount(
            userId: String,
            delta: Int,
        ) {
            counts.computeIfPresent(userId) { _, count -> (count + delta).coerceAtLeast(0) }
        }

        fun maintain(userId: String) {
            sweep(userId) { file -> isStalePartial(file) }
        }

        /** [retainedNames] are file names without extension, as [AtomicFileStore.safeName] produces them. */
        fun removeUnreferenced(
            userId: String,
            retainedNames: Set<String>,
        ) {
            sweep(userId) { file ->
                isStalePartial(file) || (file.extension != "part" && file.nameWithoutExtension !in retainedNames)
            }
        }

        /**
         * One directory listing serves the deletion, the refreshed count and the memory prune:
         * listing a large thumbnail directory is the expensive part of housekeeping, and the
         * previous code did it up to three times and then stat'ed every cached bitmap on top.
         */
        private fun sweep(
            userId: String,
            prunable: (File) -> Boolean,
        ) {
            val files = directory(userId).listFiles().orEmpty()
            var remaining = 0
            val remainingNames = HashSet<String>(files.size * 4 / 3 + 1)
            files.forEach { file ->
                if (prunable(file)) {
                    file.delete()
                } else if (file.isFile && file.extension == "thumb" && file.length() > 0L) {
                    remaining++
                    remainingNames += file.nameWithoutExtension
                }
            }
            counts[userId] = remaining
            pruneMemoryCache(userId, remainingNames)
        }

        fun clearMemory(userId: String) {
            counts.remove(userId)
            bitmaps
                .snapshot()
                .keys
                .filter { key -> key.userId == userId }
                .forEach(bitmaps::remove)
        }

        fun retainMemoryFor(userId: String?) {
            bitmaps
                .snapshot()
                .keys
                .filter { key -> key.userId != userId }
                .forEach(bitmaps::remove)
        }

        /** The encrypted file decrypted but not decoded; null when missing or unreadable. */
        fun readBytes(
            userId: String,
            nodeUid: String,
        ): ByteArray? {
            val file = file(userId, nodeUid)
            if (!file.isFile || file.length() <= 0L) return null
            return runCatching { secureFiles.read(scope(userId), file) }.getOrNull()
        }

        private fun loadFromDisk(
            key: ThumbnailKey,
            isActive: () -> Boolean,
        ): Bitmap? {
            val file = file(key.userId, key.nodeUid)
            if (!file.isFile || file.length() <= 0L) {
                file.delete()
                return null
            }
            if (!isActive()) throw CancellationException("Thumbnail load cancelled before decrypting")
            val bytes =
                try {
                    secureFiles.read(scope(key.userId), file)
                } catch (_: Exception) {
                    file.delete()
                    return null
                }
            // A fling cancels loads faster than they decode; the decode is the part worth skipping.
            if (!isActive()) throw CancellationException("Thumbnail load cancelled before decoding")
            val bitmap = ProtonThumbnailCodec.decode(bytes)
            if (bitmap == null) {
                file.delete()
                return null
            }
            file.setLastModified(clock.nowMillis())
            bitmaps.put(key, bitmap)
            return bitmap
        }

        /** Drops cached bitmaps whose file is gone, judged against one listing rather than a stat per key. */
        private fun pruneMemoryCache(
            userId: String,
            storedNames: Set<String>,
        ) {
            bitmaps
                .snapshot()
                .keys
                .filter { key -> key.userId == userId && AtomicFileStore.safeName(key.nodeUid) !in storedNames }
                .forEach(bitmaps::remove)
        }

        private fun file(
            userId: String,
            nodeUid: String,
        ): File = File(directory(userId), "${AtomicFileStore.safeName(nodeUid)}.thumb")

        private fun directory(userId: String): File =
            File(File(root, AtomicFileStore.safeName(userId)), ProtonStorageLayout.THUMBNAILS_DIRECTORY)

        private fun scope(userId: String): String = ProtonStorageLayout.mediaScope(userId)

        private fun lock(key: ThumbnailKey): Any = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

        private fun isExpired(
            file: File,
            ttlMillis: Long,
        ): Boolean = file.lastModified() <= 0L || clock.nowMillis() - file.lastModified() > ttlMillis

        private fun isStalePartial(file: File): Boolean =
            file.extension == "part" && isExpired(file, ProtonStorageLayout.STALE_PART_TTL_MILLIS)

        /** The only decoded-thumbnail cache in the process, so it must hold a whole gallery screen. */
        private fun bitmapCacheSize(): Int =
            (Runtime.getRuntime().maxMemory() / 1_024 / 12)
                .coerceIn(8 * 1_024, 48 * 1_024)
                .toInt()

        private data class ThumbnailKey(
            val userId: String,
            val nodeUid: String,
        )

        private companion object {
            const val LOCK_COUNT = 32
        }
    }

internal object ProtonThumbnailCodec {
    private const val TARGET_LONG_EDGE = 480
    private const val JPEG_QUALITY = 88

    /** The grid-sized bitmap, plus whether the source was subsampled to get there. */
    class Decoded(
        val bitmap: Bitmap,
        /** True when the delivered bytes are larger than the grid needs and are worth re-encoding. */
        val downsampled: Boolean,
    )

    fun decode(bytes: ByteArray): Bitmap? = decodeForStore(bytes)?.bitmap

    fun decodeForStore(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = ProtonPreviewDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, TARGET_LONG_EDGE)
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sampleSize
            }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val longEdge = max(decoded.width, decoded.height)
        if (longEdge <= TARGET_LONG_EDGE) return Decoded(decoded, downsampled = sampleSize > 1)
        val scale = TARGET_LONG_EDGE.toFloat() / longEdge
        val scaled =
            decoded.scale(
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
            )
        if (scaled !== decoded) decoded.recycle()
        return Decoded(scaled, downsampled = sampleSize > 1)
    }

    fun encode(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Could not encode thumbnail image"
            }
            output.toByteArray()
        }
}
