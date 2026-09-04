package com.bownee.lenswave.proton

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Encrypted on-disk store for Proton's screen-sized "preview" rendition (about 1920 px).
 *
 * Previews are a few hundred kilobytes each and are only ever shown one at a time in the viewer,
 * so the decoded-bitmap cache is tiny: the current photo and its neighbours. Any other read decrypts
 * the file and decodes it down-sampled to the caller's display size.
 */
@Singleton
internal class ProtonPreviewStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val secureFiles: SecureFileStore,
        private val clock: LenswaveClock,
    ) {
        private val root = File(context.filesDir, ProtonStorageLayout.METADATA_DIRECTORY).apply { mkdirs() }
        private val locks = Array(LOCK_COUNT) { Any() }

        /** Stored-preview counts per user; listing a large directory on every progress tick is too slow. */
        private val counts = ConcurrentHashMap<String, Int>()

        /**
         * The last few decoded previews, so a swipe back (or the same photo opened again) skips
         * the decrypt and decode. Evicted bitmaps are not recycled: the viewer may still be drawing
         * one as its placeholder, and the garbage collector reclaims them soon enough.
         */
        private val decoded = LruCache<PreviewKey, Bitmap>(DECODED_CACHE_ENTRIES)

        fun exists(
            userId: String,
            nodeUid: String,
        ): Boolean = isStoredPreview(file(userId, nodeUid))

        /**
         * File names (without extension) of every stored preview, from a single directory listing.
         * A zero-length file is excluded exactly as [exists] and [count] exclude it, so the timeline
         * never marks a preview as stored that the viewer cannot load and the queue would never
         * fetch again.
         */
        fun storedNames(userId: String): Set<String> =
            directory(userId)
                .listFiles()
                ?.mapNotNullTo(HashSet()) { file -> file.nameWithoutExtension.takeIf { isStoredPreview(file) } }
                .orEmpty()

        /**
         * Stores the bytes exactly as Proton delivered them after checking that they carry a
         * decodable image header; re-encoding a 1920 px JPEG would cost CPU for nothing.
         */
        fun write(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            require(ProtonPreviewCodec.isDecodable(bytes)) { "Proton returned an invalid preview image" }
            synchronized(lock(userId, nodeUid)) {
                dropDecoded { key -> key.userId == userId && key.nodeUid == nodeUid }
                val target = file(userId, nodeUid)
                val existed = target.isFile && target.length() > 0L
                target.parentFile?.mkdirs()
                secureFiles.write(scope(userId), target, bytes, "Could not commit preview cache file")
                target.setLastModified(clock.nowMillis())
                if (!existed) adjustCount(userId, 1)
            }
        }

        /** The decoded preview when it is still in memory from a recent [load]; never touches disk. */
        fun peek(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? = decoded.get(PreviewKey(userId, nodeUid, targetLongEdge))?.takeUnless(Bitmap::isRecycled)

        /** Decrypts and decodes the preview so its longer edge is at least [targetLongEdge] pixels. */
        fun load(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? {
            peek(userId, nodeUid, targetLongEdge)?.let { return it }
            return synchronized(lock(userId, nodeUid)) {
                peek(userId, nodeUid, targetLongEdge)?.let { return it }
                val file = file(userId, nodeUid)
                if (!isStoredPreview(file)) {
                    // A zero-length file was never counted (see [count]), so there is nothing to adjust.
                    file.delete()
                    return null
                }
                val bytes =
                    try {
                        secureFiles.read(scope(userId), file)
                    } catch (_: Exception) {
                        file.delete()
                        adjustCount(userId, -1)
                        return null
                    }
                val bitmap = ProtonPreviewCodec.decode(bytes, targetLongEdge)
                if (bitmap == null) {
                    file.delete()
                    adjustCount(userId, -1)
                    return null
                }
                file.setLastModified(clock.nowMillis())
                decoded.put(PreviewKey(userId, nodeUid, targetLongEdge), bitmap)
                bitmap
            }
        }

        fun remove(
            userId: String,
            nodeUid: String,
        ) {
            synchronized(lock(userId, nodeUid)) {
                dropDecoded { key -> key.userId == userId && key.nodeUid == nodeUid }
                val target = file(userId, nodeUid)
                val existed = target.isFile && target.length() > 0L
                target.delete()
                if (existed) adjustCount(userId, -1)
            }
        }

        fun count(userId: String): Int =
            counts.getOrPut(userId) {
                directory(userId).listFiles()?.count(::isStoredPreview) ?: 0
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
         * One directory listing serves the deletion and the refreshed count, so the next
         * [count] does not list the directory again. Only the previews actually deleted leave
         * the decoded cache: dropping the whole user would evict the preview the viewer is
         * showing every time housekeeping runs.
         */
        private fun sweep(
            userId: String,
            prunable: (File) -> Boolean,
        ) {
            val files = directory(userId).listFiles().orEmpty()
            var remaining = 0
            val deletedNames = HashSet<String>()
            files.forEach { file ->
                val stored = isStoredPreview(file)
                if (prunable(file) && file.delete()) {
                    if (stored) deletedNames += file.nameWithoutExtension
                } else if (stored) {
                    remaining++
                }
            }
            counts[userId] = remaining
            if (deletedNames.isNotEmpty()) {
                dropDecoded { key -> key.userId == userId && AtomicFileStore.safeName(key.nodeUid) in deletedNames }
            }
        }

        /** Drops memoized state for a user whose directory was deleted. */
        fun forget(userId: String) {
            counts.remove(userId)
            dropDecoded { key -> key.userId == userId }
        }

        fun retainCountsFor(userId: String?) {
            counts.keys.removeAll { key -> key != userId }
            dropDecoded { key -> key.userId != userId }
        }

        private fun dropDecoded(matches: (PreviewKey) -> Boolean) {
            decoded
                .snapshot()
                .keys
                .filter(matches)
                .forEach(decoded::remove)
        }

        private fun adjustCount(
            userId: String,
            delta: Int,
        ) {
            counts.computeIfPresent(userId) { _, count -> (count + delta).coerceAtLeast(0) }
        }

        private fun file(
            userId: String,
            nodeUid: String,
        ): File = File(directory(userId), "${AtomicFileStore.safeName(nodeUid)}.$EXTENSION")

        private fun directory(userId: String): File =
            File(File(root, AtomicFileStore.safeName(userId)), ProtonStorageLayout.PREVIEWS_DIRECTORY)

        private fun scope(userId: String): String = ProtonStorageLayout.mediaScope(userId)

        private fun lock(
            userId: String,
            nodeUid: String,
        ): Any = locks[((31 * userId.hashCode() + nodeUid.hashCode()) and Int.MAX_VALUE) % locks.size]

        private fun isStalePartial(file: File): Boolean =
            file.extension == "part" &&
                (
                    file.lastModified() <= 0L ||
                        clock.nowMillis() - file.lastModified() > ProtonStorageLayout.STALE_PART_TTL_MILLIS
                )

        /** A completed file with no bytes; partial writes are left to [isStalePartial]. */
        private fun isEmptyRendition(file: File): Boolean =
            file.extension != "part" && file.isFile && file.length() == 0L

        /** The single definition of "stored" shared by [exists], [count], [storedNames] and the sweeps. */
        private fun isStoredPreview(file: File): Boolean =
            file.extension == EXTENSION && file.isFile && file.length() > 0L

        private data class PreviewKey(
            val userId: String,
            val nodeUid: String,
            val targetLongEdge: Int,
        )

        private companion object {
            const val EXTENSION = "preview"
            const val LOCK_COUNT = 32

            /** The photo on screen plus one neighbour either side. */
            const val DECODED_CACHE_ENTRIES = 3
        }
    }

/** Pure sizing rule for decoding a stored preview at one display size. */
internal object ProtonPreviewDecodePolicy {
    /** Power-of-two subsampling that keeps the longer edge at or above [targetLongEdge]. */
    fun sampleSize(
        width: Int,
        height: Int,
        targetLongEdge: Int,
    ): Int {
        val longEdge = max(width, height)
        if (longEdge <= 0 || targetLongEdge <= 0) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }
}

/** Validates and decodes preview bytes; bytes are stored as delivered, never re-encoded. */
internal object ProtonPreviewCodec {
    fun isDecodable(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    fun decode(
        bytes: ByteArray,
        targetLongEdge: Int,
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = ProtonPreviewDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, targetLongEdge)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
