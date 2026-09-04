package com.bownee.lenswave.proton

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
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
        private val transientReadFailures = ProtonRenditionReadFailures()

        /** Stored-thumbnail counts per user; listing a large directory on every progress tick is too slow. */
        private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun exists(
            userId: String,
            nodeUid: String,
        ): Boolean = isStoredThumbnail(file(userId, nodeUid))

        /**
         * File names (without extension) of every stored thumbnail, from a single directory
         * listing. Writes are atomic renames, so a listed name is a complete file; a zero-length
         * file (a rename that hit the disk before its data did) is judged exactly as [exists] and
         * [count] judge it, so a photo listed here is one the grid can actually load.
         */
        fun storedNames(userId: String): Set<String> =
            directory(userId)
                .listFiles()
                ?.mapNotNullTo(HashSet()) { file -> file.nameWithoutExtension.takeIf { isStoredThumbnail(file) } }
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
         *
         * Decrypt and decode run outside the shard lock, as they already do for [write]: holding
         * it serialized every load that hashed into the same shard behind a decode. Two loads
         * of the same key racing each other decode twice, which is rare (the grid asks once per
         * bind) and cheaper than an in-flight map; the second one adopts the first's bitmap.
         */
        fun load(
            userId: String,
            nodeUid: String,
            isActive: () -> Boolean = { true },
        ): Bitmap? {
            val key = ThumbnailKey(userId, nodeUid)
            peek(userId, nodeUid)?.let { return it }
            val file = file(userId, nodeUid)
            if (!isStoredThumbnail(file)) {
                // A zero-length file was never counted (see [count]), so there is nothing to adjust.
                file.delete()
                return null
            }
            if (!isActive()) throw CancellationException("Thumbnail load cancelled before decrypting")
            val observed = StoredFile.of(file)
            val bytes =
                try {
                    secureFiles.read(scope(userId), file)
                } catch (error: Exception) {
                    // Only a file that is provably bad is discarded (the caller then queues it
                    // again); a Keystore or I/O hiccup keeps the file and is retried on the
                    // next bind, and must not read as "corrupt" to the caller either.
                    if (ProtonSnapshotCorruptionPolicy.isCorrupt(error)) {
                        discardUnreadable(key, file, observed)
                        return null
                    }
                    transientReadFailures.report(error)
                    throw ProtonRenditionUnavailableException(error)
                }
            transientReadFailures.recovered()
            // A fling cancels loads faster than they decode; the decode is the part worth skipping.
            if (!isActive()) throw CancellationException("Thumbnail load cancelled before decoding")
            val bitmap = ProtonThumbnailCodec.decode(bytes)
            if (bitmap == null) {
                discardUnreadable(key, file, observed)
                return null
            }
            file.setLastModified(clock.nowMillis())
            return synchronized(lock(key)) {
                val cached = bitmaps.get(key)?.takeUnless(Bitmap::isRecycled)
                if (cached != null) {
                    // A concurrent load or write got there first; theirs is at least as fresh.
                    bitmap.recycle()
                    cached
                } else {
                    bitmaps.put(key, bitmap)
                    bitmap
                }
            }
        }

        /**
         * Deletes a stored file that failed to decrypt or decode, unless a write replaced it
         * meanwhile: the file on disk is then no longer the one that was [observed] before the
         * read, or the write published its bitmap under the shard lock right after committing.
         */
        private fun discardUnreadable(
            key: ThumbnailKey,
            file: File,
            observed: StoredFile,
        ) {
            synchronized(lock(key)) {
                if (bitmaps.get(key) != null || StoredFile.of(file) != observed) return
                if (file.delete()) adjustCount(key.userId, -1)
            }
        }

        /**
         * A complete pixel decode is the validation: a thumbnail that only carries a header would
         * otherwise be stored, fail in the grid, be queued again and downloaded again forever
         * (see the instrumented test on truncated thumbnails). The decode happens outside the
         * shard lock, and the bytes are stored as delivered unless they need downsampling, so a
         * normal 480 px thumbnail is never re-encoded; the lock covers only the commit and the
         * cache put.
         *
         * The validation bitmap goes into the process-wide memory cache only when
         * [publishToMemory] asks for it: a background backfill writes thousands of thumbnails the
         * grid is not looking at, and each one it published evicted a bitmap the grid was. Without
         * publishing, the bitmap is recycled once the file is committed and the next bind decodes
         * it from disk like any other stored thumbnail.
         */
        fun write(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
            publishToMemory: Boolean = false,
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
                    if (publishToMemory) {
                        bitmaps.put(key, bitmap)
                    } else {
                        // A stale bitmap of the file this write replaced must not outlive it.
                        bitmaps.remove(key)
                        bitmap.recycle()
                    }
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

        /**
         * The first call lists the directory inside the map's own lock, so a write or removal
         * that lands while the listing runs waits and then adjusts the stored count instead of
         * being dropped by a `getOrPut` whose value is not in the map yet.
         */
        fun count(userId: String): Int =
            counts.computeIfAbsent(userId) {
                directory(userId).listFiles()?.count(::isStoredThumbnail) ?: 0
            }

        /** Only a count that has been established is adjusted; the next [count] lists from scratch otherwise. */
        private fun adjustCount(
            userId: String,
            delta: Int,
        ) {
            counts.computeIfPresent(userId) { _, count -> (count + delta).coerceAtLeast(0) }
        }

        /** Drops abandoned partial writes and zero-length files, which can never be loaded or re-fetched otherwise. */
        fun maintain(userId: String) {
            sweep(userId) { file -> isStalePartial(file) || isEmptyRendition(file) }
        }

        /** [retainedNames] are file names without extension, as [AtomicFileStore.safeName] produces them. */
        fun removeUnreferenced(
            userId: String,
            retainedNames: Set<String>,
        ) {
            sweep(userId) { file ->
                isStalePartial(file) ||
                    isEmptyRendition(file) ||
                    (file.extension != "part" && file.nameWithoutExtension !in retainedNames)
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
                } else if (isStoredThumbnail(file)) {
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

        /** A completed file with no bytes; partial writes are left to [isStalePartial]. */
        private fun isEmptyRendition(file: File): Boolean =
            file.extension != "part" && file.isFile && file.length() == 0L

        /** The single definition of "stored" shared by [exists], [count], [storedNames] and the sweeps. */
        private fun isStoredThumbnail(file: File): Boolean =
            file.extension == "thumb" && file.isFile && file.length() > 0L

        /** The only decoded-thumbnail cache in the process, so it must hold a whole gallery screen. */
        private fun bitmapCacheSize(): Int =
            (Runtime.getRuntime().maxMemory() / 1_024 / 12)
                .coerceIn(8 * 1_024, 48 * 1_024)
                .toInt()

        private data class ThumbnailKey(
            val userId: String,
            val nodeUid: String,
        )

        /** The identity of a stored file as far as a stat can tell; a replacing write changes it. */
        private data class StoredFile(
            val length: Long,
            val lastModified: Long,
        ) {
            companion object {
                fun of(file: File): StoredFile = StoredFile(file.length(), file.lastModified())
            }
        }

        private companion object {
            const val LOCK_COUNT = 32
        }
    }

/**
 * A stored rendition exists and is intact but cannot be decrypted right now (the Keystore or the
 * disk refused for a moment). Callers show nothing for it and ask again later; unlike a null
 * result, it never means the file is bad or worth downloading again.
 */
internal class ProtonRenditionUnavailableException(
    cause: Throwable,
) : RuntimeException("The stored rendition cannot be read right now", cause)

/**
 * Reports the first transient read failure of a streak and stays quiet until a read succeeds
 * again: a Keystore that is down for a minute would otherwise log once per grid cell.
 */
internal class ProtonRenditionReadFailures(
    private val reportFailure: (Throwable) -> Unit = { error ->
        LenswaveDiagnostics.reportFailure(LenswaveOperation.RENDITION_READ, error)
    },
) {
    private val reported =
        java.util.concurrent.atomic
            .AtomicBoolean()

    fun report(error: Throwable) {
        if (reported.compareAndSet(false, true)) reportFailure(error)
    }

    fun recovered() {
        reported.set(false)
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
