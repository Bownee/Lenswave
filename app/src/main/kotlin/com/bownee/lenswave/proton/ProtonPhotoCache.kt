package com.bownee.lenswave.proton

import android.content.Context
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ProtonPhotoCache @Inject constructor(
    @ApplicationContext context: Context,
    private val secureFiles: SecureFileStore,
    private val clock: LenswaveClock,
) : ProtonSyncMetadataStore,
    ProtonAccountCacheCleaner,
    ProtonTimelineCache,
    ProtonAlbumCache,
    ProtonTrashCache,
    ProtonMediaCache,
    ProtonSessionCache,
    ProtonThumbnailQueueStore {
    private val root = File(context.filesDir, "proton-photo-cache").apply { mkdirs() }
    private val originals = File(context.cacheDir, "proton-originals").apply { mkdirs() }
    private val decrypted = File(context.cacheDir, "proton-decrypted").apply {
        deleteRecursively()
        mkdirs()
    }

    /** Metadata hydration only needs availability; authenticated contents are validated when read. */
    fun thumbnailExists(userId: String, nodeUid: String): Boolean =
        thumbnailFile(userId, nodeUid).let { file ->
            (file.isFile && file.length() > 0L).also { exists ->
                if (!exists) file.delete()
            }
        }

    override fun thumbnailIsDecodable(userId: String, nodeUid: String): Boolean =
        validateThumbnailFile(userId, thumbnailFile(userId, nodeUid))

    override fun readThumbnail(userId: String, nodeUid: String): ByteArray? {
        val file = thumbnailFile(userId, nodeUid)
        return file.takeIf { validateThumbnailFile(userId, it) }
            ?.let { secureFiles.read(scope(userId), it) }
    }

    override fun writeThumbnail(userId: String, nodeUid: String, bytes: ByteArray) {
        require(isDecodableThumbnail(bytes)) { "Proton returned an invalid thumbnail image" }
        val target = thumbnailFile(userId, nodeUid)
        target.parentFile?.mkdirs()
        secureFiles.write(scope(userId), target, bytes, "Could not commit thumbnail cache file")
        target.setLastModified(clock.nowMillis())
    }

    override fun removeThumbnail(userId: String, nodeUid: String) {
        thumbnailFile(userId, nodeUid).delete()
    }

    private fun validateThumbnailFile(userId: String, file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) {
            file.delete()
            return false
        }
        val valid = runCatching {
            isDecodableThumbnail(secureFiles.read(scope(userId), file))
        }.getOrDefault(false)
        if (!valid) file.delete()
        return valid
    }

    private fun isDecodableThumbnail(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    override fun readIndex(userId: String): List<ProtonGalleryPhoto> {
        return readPhotoIndex(userId, indexFile(userId))
    }

    override fun hasTimelineSnapshot(userId: String): Boolean = hasValidArray(userId, indexFile(userId))

    override fun writeIndex(userId: String, photos: List<ProtonGalleryPhoto>) {
        writePhotoIndex(userId, indexFile(userId), photos)
    }

    override fun readAlbums(userId: String): List<ProtonAlbum> {
        val index = albumsIndexFile(userId)
        if (!index.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(readText(userId, index))
            buildList {
                for (position in 0 until array.length()) {
                    val value = array.getJSONObject(position)
                    val coverPhotoNodeUid = value.optString("coverPhotoNodeUid")
                        .takeIf { it.isNotBlank() && it != "null" }
                    add(
                        ProtonAlbum(
                            nodeUid = value.getString("nodeUid"),
                            name = value.optString("name"),
                            photoCount = value.optLong("photoCount"),
                            coverPhotoNodeUid = coverPhotoNodeUid,
                            createdAtEpochSeconds = value.optLong("createdAt"),
                            lastActivityEpochSeconds = value.optLong("lastActivity"),
                            hasCoverThumbnail = coverPhotoNodeUid?.let {
                                thumbnailExists(userId, it)
                            } == true,
                            isShared = value.optBoolean("isShared"),
                        )
                    )
                }
            }
        }.getOrElse {
            index.delete()
            emptyList()
        }
    }

    override fun hasAlbumsSnapshot(userId: String): Boolean = hasValidArray(userId, albumsIndexFile(userId))

    override fun writeAlbums(userId: String, albums: List<ProtonAlbum>) {
        val array = JSONArray()
        albums.forEach { album ->
            array.put(
                JSONObject()
                    .put("nodeUid", album.nodeUid)
                    .put("name", album.name)
                    .put("photoCount", album.photoCount)
                    .put("coverPhotoNodeUid", album.coverPhotoNodeUid)
                    .put("createdAt", album.createdAtEpochSeconds)
                    .put("lastActivity", album.lastActivityEpochSeconds)
                    .put("isShared", album.isShared)
            )
        }
        writeAtomically(userId, albumsIndexFile(userId), array.toString(), "Could not commit Proton albums index")
    }

    override fun readAlbumPhotos(userId: String, albumUid: String): List<ProtonGalleryPhoto> =
        readPhotoIndex(userId, albumPhotosIndexFile(userId, albumUid))

    override fun hasAlbumPhotosSnapshot(userId: String, albumUid: String): Boolean =
        hasValidArray(userId, albumPhotosIndexFile(userId, albumUid))

    override fun writeAlbumPhotos(userId: String, albumUid: String, photos: List<ProtonGalleryPhoto>) {
        writePhotoIndex(userId, albumPhotosIndexFile(userId, albumUid), photos)
    }

    override fun readTrash(userId: String): List<ProtonTrashPhoto> {
        val index = trashIndexFile(userId)
        if (!index.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(readText(userId, index))
            buildList {
                for (position in 0 until array.length()) {
                    val value = array.getJSONObject(position)
                    val nodeUid = value.getString("nodeUid")
                    add(
                        ProtonTrashPhoto(
                            nodeUid = nodeUid,
                            trashedAtEpochSeconds = value.getLong("trashedAt"),
                            hasThumbnail = thumbnailExists(userId, nodeUid),
                            displayName = value.optString("displayName"),
                        )
                    )
                }
            }
        }.getOrElse {
            index.delete()
            emptyList()
        }
    }

    override fun hasTrashSnapshot(userId: String): Boolean = hasValidArray(userId, trashIndexFile(userId))

    override fun writeTrash(userId: String, photos: List<ProtonTrashPhoto>) {
        val array = JSONArray()
        photos.forEach { photo ->
            array.put(
                JSONObject()
                    .put("nodeUid", photo.nodeUid)
                    .put("trashedAt", photo.trashedAtEpochSeconds)
                    .put("displayName", photo.displayName)
            )
        }
        writeAtomically(userId, trashIndexFile(userId), array.toString(), "Could not commit Proton Trash index")
    }

    override fun readLastSuccessfulSync(userId: String, source: String): Long =
        runCatching { readText(userId, syncMetadataFile(userId, source)).toLong() }.getOrDefault(0L)

    override fun writeLastSuccessfulSync(userId: String, source: String, timestampMillis: Long) {
        writeAtomically(
            userId,
            syncMetadataFile(userId, source),
            timestampMillis.toString(),
            "Could not commit Proton sync metadata",
        )
    }

    override fun readThumbnailQueue(userId: String): List<ProtonThumbnailQueueEntry> {
        val queue = thumbnailQueueFile(userId)
        if (!queue.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(readText(userId, queue))
            buildList {
                for (position in 0 until array.length()) {
                    val value = array.getJSONObject(position)
                    val sources = value.getJSONArray("sources")
                    add(
                        ProtonThumbnailQueueEntry(
                            nodeUid = value.getString("nodeUid"),
                            sources = buildSet {
                                for (sourcePosition in 0 until sources.length()) {
                                    add(sources.getString(sourcePosition))
                                }
                            },
                            priority = value.getInt("priority"),
                            order = value.getLong("order"),
                            retryCount = value.optInt("retryCount"),
                            retryAtMillis = value.optLong("retryAtMillis"),
                        )
                    )
                }
            }
        }.getOrElse {
            queue.delete()
            emptyList()
        }
    }

    override fun writeThumbnailQueue(userId: String, entries: List<ProtonThumbnailQueueEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("nodeUid", entry.nodeUid)
                    .put("sources", JSONArray(entry.sources.sorted()))
                    .put("priority", entry.priority)
                    .put("order", entry.order)
                    .put("retryCount", entry.retryCount)
                    .put("retryAtMillis", entry.retryAtMillis)
            )
        }
        writeAtomically(
            userId,
            thumbnailQueueFile(userId),
            array.toString(),
            "Could not commit Proton thumbnail queue",
        )
    }

    override fun reconcileAlbums(userId: String, remoteAlbumUids: Collection<String>) {
        val validNames = remoteAlbumUids.mapTo(mutableSetOf(), ::safeName)
        albumPhotosDirectory(userId).listFiles()?.forEach { file ->
            if (isStalePartial(file) || file.extension != "part" && file.nameWithoutExtension !in validNames) {
                file.delete()
            }
        }
    }

    override fun readOriginal(userId: String, nodeUid: String): File? {
        val file = originalFile(userId, nodeUid)
        if (!file.isFile || file.length() <= 0L || isExpired(file, ORIGINAL_TTL_MILLIS)) {
            file.delete()
            return null
        }
        val materialized = decryptedOriginalFile(userId, nodeUid)
        if (materialized.isFile && !isExpired(materialized, DECRYPTED_TTL_MILLIS)) {
            materialized.setLastModified(clock.nowMillis())
            file.setLastModified(clock.nowMillis())
            return materialized
        }
        return runCatching {
            materialized.delete()
            secureFiles.decryptFile(scope(userId), file, materialized)
            file.setLastModified(clock.nowMillis())
            materialized.setLastModified(clock.nowMillis())
            materialized
        }.getOrElse {
            materialized.delete()
            file.delete()
            null
        }
    }

    override fun createOriginalTarget(userId: String, nodeUid: String): Pair<File, File> {
        val target = originalFile(userId, nodeUid)
        val transientDirectory = decryptedDirectory(userId).apply { mkdirs() }
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${safeName(nodeUid)}.", ".part", transientDirectory)
        return temporary to target
    }

    override fun commitOriginal(userId: String, nodeUid: String, plaintext: File, target: File): File {
        return try {
            secureFiles.encryptFile(scope(userId), plaintext, target, "Could not protect downloaded photo")
            target.setLastModified(clock.nowMillis())
            readOriginal(userId, nodeUid) ?: error("Could not verify protected downloaded photo")
        } finally {
            plaintext.delete()
        }
    }

    override fun onOriginalStored(userId: String, target: File) {
        target.setLastModified(clock.nowMillis())
        trimDirectory(originalDirectory(userId), ORIGINAL_CACHE_LIMIT_BYTES, ORIGINAL_TTL_MILLIS)
    }

    override fun trimUser(userId: String) {
        trimDirectory(originalDirectory(userId), ORIGINAL_CACHE_LIMIT_BYTES, ORIGINAL_TTL_MILLIS)
        trimThumbnails(userId)
        trimDirectory(decryptedDirectory(userId), DECRYPTED_CACHE_LIMIT_BYTES, DECRYPTED_TTL_MILLIS)
    }

    internal fun trimThumbnails(
        userId: String,
        limitBytes: Long = THUMBNAIL_CACHE_LIMIT_BYTES,
        ttlMillis: Long = THUMBNAIL_TTL_MILLIS,
    ) {
        trimDirectory(thumbnailDirectory(userId), limitBytes, ttlMillis)
    }

    override fun reconcilePhotos(
        userId: String,
        cachedNodeUids: Collection<String>,
        remoteNodeUids: Collection<String>,
    ): ProtonPhotoChanges {
        val changes = ProtonPhotoReconciliation.compare(cachedNodeUids, remoteNodeUids)
        val albumNodeUids = albumReferencedNodeUids(userId)
        changes.removedNodeUids.forEach { nodeUid ->
            if (nodeUid in albumNodeUids) return@forEach
            thumbnailFile(userId, nodeUid).delete()
            originalFile(userId, nodeUid).delete()
            decryptedOriginalFile(userId, nodeUid).delete()
        }
        val validNames = (remoteNodeUids + albumNodeUids).mapTo(mutableSetOf(), ::safeName)
        thumbnailDirectory(userId).listFiles()?.forEach { file ->
            if (isStalePartial(file) || file.extension != "part" && file.nameWithoutExtension !in validNames) {
                file.delete()
            }
        }
        originalDirectory(userId).listFiles()?.forEach { file ->
            if (isStalePartial(file) || file.extension != "part" && file.nameWithoutExtension !in validNames) {
                file.delete()
            }
        }
        decryptedDirectory(userId).listFiles()?.forEach { file ->
            if (isStalePartial(file) || file.extension != "part" && file.nameWithoutExtension !in validNames) {
                file.delete()
            }
        }
        return changes
    }

    override fun removePhotos(userId: String, nodeUids: Collection<String>) {
        val removed = nodeUids.toSet()
        removed.forEach { nodeUid ->
            thumbnailFile(userId, nodeUid).delete()
            originalFile(userId, nodeUid).delete()
            decryptedOriginalFile(userId, nodeUid).delete()
        }
        writeIndex(userId, readIndex(userId).filterNot { it.nodeUid in removed })
        val albumCounts = mutableMapOf<String, Long>()
        albumPhotosDirectory(userId).listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach { file ->
                val remaining = readPhotoIndex(userId, file).filterNot { it.nodeUid in removed }
                writePhotoIndex(userId, file, remaining)
                albumCounts[file.nameWithoutExtension] = remaining.size.toLong()
            }
        if (albumCounts.isNotEmpty()) {
            writeAlbums(userId, readAlbums(userId).map { album ->
                albumCounts[safeName(album.nodeUid)]?.let { count -> album.copy(photoCount = count) } ?: album
            })
        }
    }

    override fun clearUser(userId: String) {
        val indexDeleted = userDirectory(userId).deleteRecursively()
        val originalsDeleted = originalDirectory(userId).deleteRecursively()
        val decryptedDeleted = decryptedDirectory(userId).deleteRecursively()
        check(indexDeleted && originalsDeleted && decryptedDeleted) { "Could not remove cached Proton media" }
        secureFiles.deleteKey(scope(userId))
    }

    override fun retainOnlyUser(userId: String?) {
        val retainedName = userId?.let(::safeName)
        root.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
            check(directory.deleteRecursively()) { "Could not remove orphaned Proton cache" }
        }
        originals.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
            check(directory.deleteRecursively()) { "Could not remove orphaned Proton originals" }
        }
        decrypted.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
            check(directory.deleteRecursively()) { "Could not remove orphaned decrypted Proton media" }
        }
    }

    private fun thumbnailFile(userId: String, nodeUid: String): File =
        File(thumbnailDirectory(userId), "${safeName(nodeUid)}.thumb")

    private fun thumbnailDirectory(userId: String): File = File(userDirectory(userId), "thumbnails")

    private fun originalFile(userId: String, nodeUid: String): File =
        File(originalDirectory(userId), "${safeName(nodeUid)}.image")

    private fun originalDirectory(userId: String): File = File(originals, safeName(userId))

    private fun decryptedOriginalFile(userId: String, nodeUid: String): File =
        File(decryptedDirectory(userId), "${safeName(nodeUid)}.image")

    private fun decryptedDirectory(userId: String): File = File(decrypted, safeName(userId))

    private fun indexFile(userId: String): File = File(userDirectory(userId), "index.json")

    private fun albumsIndexFile(userId: String): File = File(userDirectory(userId), "albums.json")

    private fun trashIndexFile(userId: String): File = File(userDirectory(userId), "trash.json")

    private fun albumPhotosIndexFile(userId: String, albumUid: String): File =
        File(albumPhotosDirectory(userId), "${safeName(albumUid)}.json")

    private fun albumPhotosDirectory(userId: String): File = File(userDirectory(userId), "album-photos")

    private fun syncMetadataFile(userId: String, source: String): File =
        File(File(userDirectory(userId), "sync"), "${safeName(source)}.timestamp")

    private fun thumbnailQueueFile(userId: String): File =
        File(userDirectory(userId), "thumbnail-queue.json")

    private fun userDirectory(userId: String): File = File(root, safeName(userId))

    private fun readPhotoIndex(userId: String, index: File): List<ProtonGalleryPhoto> {
        if (!index.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(readText(userId, index))
            buildList {
                for (position in 0 until array.length()) {
                    val value = array.getJSONObject(position)
                    val nodeUid = value.getString("nodeUid")
                    add(
                        ProtonGalleryPhoto(
                            nodeUid = nodeUid,
                            captureTimeEpochSeconds = value.getLong("captureTime"),
                            hasThumbnail = thumbnailExists(userId, nodeUid),
                        )
                    )
                }
            }
        }.getOrElse {
            index.delete()
            emptyList()
        }
    }

    private fun writePhotoIndex(userId: String, target: File, photos: List<ProtonGalleryPhoto>) {
        val array = JSONArray()
        photos.forEach { photo ->
            array.put(
                JSONObject()
                    .put("nodeUid", photo.nodeUid)
                    .put("captureTime", photo.captureTimeEpochSeconds)
            )
        }
        writeAtomically(userId, target, array.toString(), "Could not commit Proton Photos index")
    }

    private fun albumReferencedNodeUids(userId: String): Set<String> = buildSet {
        readAlbums(userId).mapNotNullTo(this) { it.coverPhotoNodeUid }
        albumPhotosDirectory(userId).listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach { file -> readPhotoIndex(userId, file).mapTo(this, ProtonGalleryPhoto::nodeUid) }
    }

    private fun writeAtomically(userId: String, target: File, contents: String, failureMessage: String) {
        secureFiles.writeText(scope(userId), target, contents, failureMessage)
    }

    private fun hasValidArray(userId: String, file: File): Boolean {
        if (!file.isFile) return false
        return runCatching { JSONArray(readText(userId, file)); true }.getOrElse {
            file.delete()
            false
        }
    }

    private fun readText(userId: String, file: File): String = secureFiles.readText(scope(userId), file)

    private fun scope(userId: String): String = "proton-media:$userId"

    private fun isStalePartial(file: File): Boolean =
        file.extension == "part" && isExpired(file, STALE_PART_TTL_MILLIS)

    private fun isExpired(file: File, ttlMillis: Long): Boolean =
        file.lastModified() <= 0L || clock.nowMillis() - file.lastModified() > ttlMillis

    private fun trimDirectory(directory: File, maxBytes: Long, ttlMillis: Long) {
        directory.listFiles()?.filter(File::isFile)?.filter { isExpired(it, ttlMillis) }?.forEach(File::delete)
        val remaining = directory.listFiles()?.filter(File::isFile)
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var totalBytes = remaining.sumOf(File::length)
        for (file in remaining) {
            if (totalBytes <= maxBytes) break
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    private fun safeName(value: String): String = AtomicFileStore.safeName(value)

    private companion object {
        const val ORIGINAL_CACHE_LIMIT_BYTES = 256L * 1024L * 1024L
        const val DECRYPTED_CACHE_LIMIT_BYTES = 64L * 1024L * 1024L
        const val THUMBNAIL_CACHE_LIMIT_BYTES = 96L * 1024L * 1024L
        const val ORIGINAL_TTL_MILLIS = 60L * 60L * 1_000L
        const val DECRYPTED_TTL_MILLIS = 30L * 60L * 1_000L
        const val THUMBNAIL_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val STALE_PART_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
