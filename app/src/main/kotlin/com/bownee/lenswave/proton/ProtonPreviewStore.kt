package com.bownee.lenswave.proton

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.metadata.ImageOrientationPolicy
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import com.bownee.lenswave.viewer.PhotoBaseDecodePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.WeakHashMap
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
        private val transientReadFailures = ProtonRenditionReadFailures()

        /** Stored-preview counts per user; listing a large directory on every progress tick is too slow. */
        private val counts = ConcurrentHashMap<String, Int>()

        /**
         * The last few decoded previews, so a swipe back (or the same photo opened again) skips
         * the decrypt and decode. Sized in kilobytes against a share of the heap: a screen-sized
         * preview is about 5.5 MB in RGB 565 (11 MB when the container can carry transparency),
         * so counting entries would pin tens of megabytes for good. Evicted bitmaps are not
         * recycled: the viewer may still be drawing one as its placeholder, and the garbage
         * collector reclaims them soon enough.
         */
        private val decoded =
            object : LruCache<PreviewKey, Bitmap>(decodedCacheSizeKilobytes()) {
                override fun sizeOf(
                    key: PreviewKey,
                    value: Bitmap,
                ): Int = (value.byteCount / 1_024).coerceAtLeast(1)
            }

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
         * decodable image header; re-encoding a 1920 px JPEG would cost CPU for nothing. The
         * cache scan for stale decodes runs before the shard lock is taken; the lock covers only
         * the commit and the removal of the keys found.
         */
        fun write(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            require(ProtonPreviewCodec.isDecodable(bytes)) { "Proton returned an invalid preview image" }
            val stale = decodedKeys { key -> key.userId == userId && key.nodeUid == nodeUid }
            synchronized(lock(userId, nodeUid)) {
                stale.forEach(decoded::remove)
                val target = file(userId, nodeUid)
                val existed = target.isFile && target.length() > 0L
                target.parentFile?.mkdirs()
                secureFiles.write(scope(userId), target, bytes, "Could not commit preview cache file")
                if (!existed) adjustCount(userId, 1)
            }
        }

        /**
         * The decoded preview when it is still in memory from a recent [load]; never touches
         * disk. The bitmap holds the pixels as stored; [ProtonPreviewOrientation.of] tells how
         * to show them.
         */
        fun peek(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? = decoded.get(PreviewKey(userId, nodeUid, targetLongEdge))?.takeUnless(Bitmap::isRecycled)

        /**
         * Decrypts and decodes the preview so its longer edge is at least [targetLongEdge]
         * pixels. The bitmap holds the pixels as stored, in the file's own axes; its EXIF
         * orientation is recorded with [ProtonPreviewOrientation] for the viewer to draw through,
         * so a rotated photo does not pay for a second full-size copy.
         *
         * Decrypt and decode run outside the shard lock, as they do in [ProtonThumbnailStore]:
         * holding it serialized every load that hashed into the same shard behind a decode. Two
         * loads of the same key racing each other decode twice; the second adopts the first's
         * bitmap.
         */
        fun load(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? {
            val key = PreviewKey(userId, nodeUid, targetLongEdge)
            peek(userId, nodeUid, targetLongEdge)?.let { return it }
            val file = file(userId, nodeUid)
            if (!isStoredPreview(file)) {
                // A zero-length file was never counted (see [count]), so there is nothing to adjust.
                file.delete()
                return null
            }
            val version = FileVersion.of(file)
            val bytes =
                try {
                    secureFiles.read(scope(userId), file)
                } catch (error: Exception) {
                    // A provably bad file goes; a Keystore or I/O hiccup keeps it, and the
                    // viewer simply shows no preview until the next open.
                    if (ProtonSnapshotCorruptionPolicy.isCorrupt(error)) {
                        discardUnreadable(userId, nodeUid, file, version)
                    } else {
                        transientReadFailures.report(error)
                    }
                    return null
                }
            transientReadFailures.recovered()
            val preview = ProtonPreviewCodec.decode(bytes, targetLongEdge)
            if (preview == null) {
                discardUnreadable(userId, nodeUid, file, version)
                return null
            }
            return synchronized(lock(userId, nodeUid)) {
                val cached = decoded.get(key)?.takeUnless(Bitmap::isRecycled)
                if (cached != null) {
                    // A concurrent load got there first; theirs is at least as fresh.
                    preview.bitmap.recycle()
                    cached
                } else {
                    ProtonPreviewOrientation.record(preview.bitmap, preview.orientation)
                    decoded.put(key, preview.bitmap)
                    preview.bitmap
                }
            }
        }

        /**
         * Deletes a stored file that failed to decrypt or decode, unless a write replaced it
         * meanwhile: the file only goes while it is still the one the read saw.
         */
        private fun discardUnreadable(
            userId: String,
            nodeUid: String,
            file: File,
            readVersion: FileVersion,
        ) {
            synchronized(lock(userId, nodeUid)) {
                if (FileVersion.of(file) != readVersion) return
                if (file.delete()) adjustCount(userId, -1)
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

        /** Lists inside the map's lock so a concurrent write or removal adjusts the count rather than being dropped. */
        fun count(userId: String): Int =
            counts.computeIfAbsent(userId) {
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

        private fun decodedKeys(matches: (PreviewKey) -> Boolean): List<PreviewKey> =
            decoded
                .snapshot()
                .keys
                .filter(matches)

        private fun dropDecoded(matches: (PreviewKey) -> Boolean) {
            decodedKeys(matches).forEach(decoded::remove)
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

        /** Identifies the bytes a read saw, so a failed decode never deletes a file a write has since replaced. */
        private data class FileVersion(
            val length: Long,
            val lastModified: Long,
        ) {
            companion object {
                fun of(file: File) = FileVersion(file.length(), file.lastModified())
            }
        }

        private companion object {
            const val EXTENSION = "preview"
            const val LOCK_COUNT = 32

            /**
             * Room for the photo on screen and a neighbour or two: a twenty-fourth of the heap,
             * held between one large preview and a handful so a small heap still gets a swipe
             * back. Half the former share, since an opaque preview now decodes to half the bytes.
             */
            fun decodedCacheSizeKilobytes(): Int =
                (Runtime.getRuntime().maxMemory() / 1_024 / 24)
                    .coerceIn(8L * 1_024, 24L * 1_024)
                    .toInt()
        }
    }

/**
 * A decoded preview: the pixels as stored in the file and the EXIF orientation that turns them
 * into the picture as it is meant to be seen.
 */
internal class ProtonPreview(
    val bitmap: Bitmap,
    val orientation: Int,
)

/**
 * The EXIF orientation of each preview bitmap the store has handed out, keyed by the bitmap
 * itself. The store's callers receive plain bitmaps through the gallery's data-source interfaces;
 * this lets the viewer draw them oriented without the store copying every rotated preview.
 * Entries go with their bitmaps. A bitmap nobody recorded is shown as stored.
 */
internal object ProtonPreviewOrientation {
    private val orientations = WeakHashMap<Bitmap, Int>()

    fun record(
        bitmap: Bitmap,
        orientation: Int,
    ) {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return
        synchronized(orientations) { orientations[bitmap] = orientation }
    }

    fun of(bitmap: Bitmap): Int =
        synchronized(orientations) { orientations[bitmap] } ?: ExifInterface.ORIENTATION_NORMAL
}

/** Pure decoding rules for a stored preview at one display size. */
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

    /**
     * True when the preview can be decoded to 16-bit RGB 565: the container cannot carry
     * transparency, so half the memory of ARGB 8888 loses nothing the file had.
     */
    fun decodesOpaque(mimeType: String?): Boolean = PhotoBaseDecodePolicy.isOpaque(mimeType)

    /**
     * Whether the EXIF orientation tag has to be parsed at all: the HEIF family is rotated by
     * the decoder itself, so its tag is never applied and not worth reading.
     */
    fun readsExifOrientation(mimeType: String?): Boolean = ImageOrientationPolicy.appliesExifOrientation(mimeType)
}

/** Validates and decodes preview bytes; bytes are stored as delivered, never re-encoded. */
internal object ProtonPreviewCodec {
    fun isDecodable(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /**
     * Decodes the preview as stored, opaque containers to RGB 565, and reads the EXIF
     * orientation once to carry alongside. The bitmap is not rotated: the viewer draws it
     * through the orientation's matrix, which costs nothing per frame where a rotated copy cost
     * a second screen-sized allocation per preview.
     */
    fun decode(
        bytes: ByteArray,
        targetLongEdge: Int,
    ): ProtonPreview? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val mimeType = bounds.outMimeType
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig =
                    if (ProtonPreviewDecodePolicy.decodesOpaque(mimeType)) {
                        Bitmap.Config.RGB_565
                    } else {
                        Bitmap.Config.ARGB_8888
                    }
                inSampleSize = ProtonPreviewDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, targetLongEdge)
            }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val orientation =
            if (ProtonPreviewDecodePolicy.readsExifOrientation(mimeType)) {
                runCatching {
                    ExifInterface(ByteArrayInputStream(bytes))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        return ProtonPreview(decoded, ImageOrientationPolicy.effectiveOrientation(mimeType, orientation))
    }
}
