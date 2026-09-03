package com.bownee.lenswave.proton

import android.content.Context
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ProtonPhotoCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val secureFiles: SecureFileStore,
        private val clock: LenswaveClock,
        private val thumbnails: ProtonThumbnailStore,
    ) : ProtonSyncMetadataStore,
        ProtonAccountCacheCleaner,
        ProtonTimelineCache,
        ProtonAlbumCache,
        ProtonMediaCache,
        ProtonSessionCache,
        ProtonThumbnailQueueStore {
        private val root = File(context.filesDir, ProtonStorageLayout.METADATA_DIRECTORY).apply { mkdirs() }
        private val originals = File(context.cacheDir, ProtonStorageLayout.ORIGINALS_DIRECTORY).apply { mkdirs() }
        private val decrypted = File(context.cacheDir, ProtonStorageLayout.DECRYPTED_DIRECTORY)

        /**
         * Plaintext copies from a previous process are wiped once, on the first session activation,
         * which runs on an I/O dispatcher. Doing it in the constructor would delete potentially
         * hundreds of megabytes on the main thread while Hilt builds the object graph.
         */
        @Volatile private var decryptedWiped = false

        /** Metadata hydration only needs availability; authenticated contents are validated when read. */
        override fun thumbnailExists(
            userId: String,
            nodeUid: String,
        ): Boolean = thumbnails.exists(userId, nodeUid)

        override fun loadThumbnail(
            userId: String,
            nodeUid: String,
        ) = thumbnails.load(userId, nodeUid)

        override fun peekThumbnail(
            userId: String,
            nodeUid: String,
        ) = thumbnails.peek(userId, nodeUid)

        override fun writeThumbnail(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            thumbnails.write(userId, nodeUid, bytes)
        }

        override fun removeThumbnail(
            userId: String,
            nodeUid: String,
        ) {
            thumbnails.remove(userId, nodeUid)
        }

        override fun thumbnailCount(userId: String): Int = thumbnails.count(userId)

        override fun readIndex(userId: String): List<ProtonGalleryPhoto> = readPhotoIndex(userId, indexFile(userId))

        override fun hasTimelineSnapshot(userId: String): Boolean = hasValidArray(userId, indexFile(userId))

        override fun writeIndex(
            userId: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            writePhotoIndex(userId, indexFile(userId), photos)
        }

        override fun readTag(
            userId: String,
            tag: ProtonMediaTag,
        ): List<ProtonGalleryPhoto> = readPhotoIndex(userId, tagIndexFile(userId, tag))

        override fun hasTagSnapshot(
            userId: String,
            tag: ProtonMediaTag,
        ): Boolean = hasValidArray(userId, tagIndexFile(userId, tag))

        override fun writeTag(
            userId: String,
            tag: ProtonMediaTag,
            photos: List<ProtonGalleryPhoto>,
        ) {
            writePhotoIndex(userId, tagIndexFile(userId, tag), photos)
        }

        override fun readAlbums(userId: String): List<ProtonAlbum> {
            val index = albumsIndexFile(userId)
            if (!index.isFile) return emptyList()
            return runCatching {
                val array = JSONArray(readText(userId, index))
                buildList {
                    for (position in 0 until array.length()) {
                        val value = array.getJSONObject(position)
                        val coverPhotoNodeUid =
                            value
                                .optString("coverPhotoNodeUid")
                                .takeIf { it.isNotBlank() && it != "null" }
                        add(
                            ProtonAlbum(
                                nodeUid = value.getString("nodeUid"),
                                name = value.optString("name"),
                                photoCount = value.optLong("photoCount"),
                                coverPhotoNodeUid = coverPhotoNodeUid,
                                createdAtEpochSeconds = value.optLong("createdAt"),
                                lastActivityEpochSeconds = value.optLong("lastActivity"),
                                hasCoverThumbnail =
                                    coverPhotoNodeUid?.let {
                                        thumbnailExists(userId, it)
                                    } == true,
                                isShared = value.optBoolean("isShared"),
                            ),
                        )
                    }
                }
            }.getOrElse {
                index.delete()
                emptyList()
            }
        }

        override fun hasAlbumsSnapshot(userId: String): Boolean = hasValidArray(userId, albumsIndexFile(userId))

        override fun writeAlbums(
            userId: String,
            albums: List<ProtonAlbum>,
        ) {
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
                        .put("isShared", album.isShared),
                )
            }
            writeAtomically(userId, albumsIndexFile(userId), array.toString(), "Could not commit Proton albums index")
        }

        override fun readAlbumPhotos(
            userId: String,
            albumUid: String,
        ): List<ProtonGalleryPhoto> = readPhotoIndex(userId, albumPhotosIndexFile(userId, albumUid))

        override fun hasAlbumPhotosSnapshot(
            userId: String,
            albumUid: String,
        ): Boolean = hasValidArray(userId, albumPhotosIndexFile(userId, albumUid))

        override fun writeAlbumPhotos(
            userId: String,
            albumUid: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            writePhotoIndex(userId, albumPhotosIndexFile(userId, albumUid), photos)
        }

        override fun readLastSuccessfulSync(
            userId: String,
            source: String,
        ): Long = runCatching { readText(userId, syncMetadataFile(userId, source)).toLong() }.getOrDefault(0L)

        override fun writeLastSuccessfulSync(
            userId: String,
            source: String,
            timestampMillis: Long,
        ) {
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
                        val sourceCaptureTimes =
                            value.optJSONObject("sourceCaptureTimes")?.let { stored ->
                                buildMap {
                                    val sources = stored.keys()
                                    while (sources.hasNext()) {
                                        val source = sources.next()
                                        put(source, stored.getLong(source))
                                    }
                                }
                            } ?: run {
                                val legacySources = value.getJSONArray("sources")
                                buildMap {
                                    for (sourcePosition in 0 until legacySources.length()) {
                                        put(legacySources.getString(sourcePosition), Long.MIN_VALUE)
                                    }
                                }
                            }
                        add(
                            ProtonThumbnailQueueEntry(
                                nodeUid = value.getString("nodeUid"),
                                sourceCaptureTimes = sourceCaptureTimes,
                                retryCount = value.optInt("retryCount"),
                                retryAtMillis = value.optLong("retryAtMillis"),
                            ),
                        )
                    }
                }
            }.getOrElse {
                queue.delete()
                emptyList()
            }
        }

        override fun writeThumbnailQueue(
            userId: String,
            entries: List<ProtonThumbnailQueueEntry>,
        ) {
            val array = JSONArray()
            entries.forEach { entry ->
                val sourceCaptureTimes = JSONObject()
                entry.sourceCaptureTimes.toSortedMap().forEach { (source, captureTime) ->
                    sourceCaptureTimes.put(source, captureTime)
                }
                array.put(
                    JSONObject()
                        .put("nodeUid", entry.nodeUid)
                        .put("sourceCaptureTimes", sourceCaptureTimes)
                        .put("retryCount", entry.retryCount)
                        .put("retryAtMillis", entry.retryAtMillis),
                )
            }
            writeAtomically(
                userId,
                thumbnailQueueFile(userId),
                array.toString(),
                "Could not commit Proton thumbnail queue",
            )
        }

        override fun reconcileAlbums(
            userId: String,
            remoteAlbumUids: Collection<String>,
        ) {
            val validNames = remoteAlbumUids.mapTo(mutableSetOf(), ::safeName)
            albumPhotosDirectory(userId).listFiles()?.forEach { file ->
                if (file.isPrunable(validNames)) {
                    file.delete()
                }
            }
        }

        override fun readOriginal(
            userId: String,
            nodeUid: String,
        ): File? {
            val file = originalFile(userId, nodeUid)
            if (!file.isFile || file.length() <= 0L) {
                file.delete()
                return null
            }
            val materialized = decryptedOriginalFile(userId, nodeUid)
            if (materialized.isFile && !isExpired(materialized, ProtonStorageLayout.DECRYPTED_TTL_MILLIS)) {
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

        override fun createOriginalTarget(
            userId: String,
            nodeUid: String,
        ): Pair<File, File> {
            val target = originalFile(userId, nodeUid)
            val materialized = decryptedOriginalFile(userId, nodeUid)
            materialized.parentFile?.mkdirs()
            target.parentFile?.mkdirs()
            check(materialized.delete() || !materialized.exists()) {
                "Could not replace materialized Proton media"
            }
            return materialized to target
        }

        override fun commitOriginal(
            userId: String,
            nodeUid: String,
            plaintext: File,
            target: File,
        ): File {
            require(plaintext == decryptedOriginalFile(userId, nodeUid)) {
                "Downloaded Proton media must use its materialized cache target"
            }
            secureFiles.encryptFile(scope(userId), plaintext, target, "Could not protect downloaded photo")
            val storedAt = clock.nowMillis()
            target.setLastModified(storedAt)
            plaintext.setLastModified(storedAt)
            return plaintext
        }

        override fun onOriginalStored(
            userId: String,
            target: File,
        ) {
            // Encrypted originals are kept without a size or age limit; reconciliation removes the
            // ones whose photos no longer exist, and disconnecting erases them all.
            target.setLastModified(clock.nowMillis())
        }

        override fun trimUser(userId: String) {
            thumbnails.maintain(userId)
            wipeStaleDecryptedCopies()
            expireFiles(decryptedDirectory(userId), ProtonStorageLayout.DECRYPTED_TTL_MILLIS)
        }

        @Synchronized
        private fun wipeStaleDecryptedCopies() {
            if (decryptedWiped) return
            decrypted.deleteRecursively()
            decrypted.mkdirs()
            decryptedWiped = true
        }

        override fun reconcilePhotos(
            userId: String,
            cachedNodeUids: Collection<String>,
            remoteNodeUids: Collection<String>,
        ) {
            val changes = ProtonPhotoReconciliation.compare(cachedNodeUids, remoteNodeUids)
            val remote = remoteNodeUids.toSet()
            ProtonMediaTag.entries.forEach { tag ->
                if (!hasTagSnapshot(userId, tag)) return@forEach
                val tagged = readTag(userId, tag)
                val retained = tagged.filter { it.nodeUid in remote }
                if (retained.size != tagged.size) writeTag(userId, tag, retained)
            }
            val retainedNodeUids = nonTimelineReferencedNodeUids(userId)
            changes.removedNodeUids.forEach { nodeUid ->
                if (nodeUid in retainedNodeUids) return@forEach
                thumbnails.remove(userId, nodeUid)
                originalFile(userId, nodeUid).delete()
                decryptedOriginalFile(userId, nodeUid).delete()
            }
            val validNames = (remoteNodeUids + retainedNodeUids).mapTo(mutableSetOf(), ::safeName)
            thumbnails.removeUnreferenced(userId, remoteNodeUids + retainedNodeUids)
            originalDirectory(userId).listFiles()?.forEach { file ->
                if (file.isPrunable(validNames)) {
                    file.delete()
                }
            }
            decryptedDirectory(userId).listFiles()?.forEach { file ->
                if (file.isPrunable(validNames)) {
                    file.delete()
                }
            }
        }

        override fun removePhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
            val removed = nodeUids.toSet()
            removed.forEach { nodeUid ->
                removeThumbnail(userId, nodeUid)
                originalFile(userId, nodeUid).delete()
                decryptedOriginalFile(userId, nodeUid).delete()
            }
            writeIndex(userId, readIndex(userId).filterNot { it.nodeUid in removed })
            ProtonMediaTag.entries.forEach { tag ->
                if (hasTagSnapshot(userId, tag)) {
                    writeTag(userId, tag, readTag(userId, tag).filterNot { it.nodeUid in removed })
                }
            }
            val albumCounts = mutableMapOf<String, Long>()
            albumPhotosDirectory(userId)
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.forEach { file ->
                    val remaining = readPhotoIndex(userId, file).filterNot { it.nodeUid in removed }
                    writePhotoIndex(userId, file, remaining)
                    albumCounts[file.nameWithoutExtension] = remaining.size.toLong()
                }
            if (albumCounts.isNotEmpty()) {
                writeAlbums(
                    userId,
                    readAlbums(userId).map { album ->
                        albumCounts[safeName(album.nodeUid)]?.let { count -> album.copy(photoCount = count) } ?: album
                    },
                )
            }
        }

        override fun clearUser(userId: String) {
            thumbnails.clearMemory(userId)
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
            thumbnails.retainMemoryFor(userId)
        }

        private fun originalFile(
            userId: String,
            nodeUid: String,
        ): File = File(originalDirectory(userId), "${safeName(nodeUid)}.image")

        private fun originalDirectory(userId: String): File = File(originals, safeName(userId))

        private fun decryptedOriginalFile(
            userId: String,
            nodeUid: String,
        ): File = File(decryptedDirectory(userId), "${safeName(nodeUid)}.image")

        private fun decryptedDirectory(userId: String): File = File(decrypted, safeName(userId))

        private fun indexFile(userId: String): File = File(userDirectory(userId), "index.json")

        private fun tagIndexFile(
            userId: String,
            tag: ProtonMediaTag,
        ): File = File(File(userDirectory(userId), "tags"), "${tag.name.lowercase()}.json")

        private fun albumsIndexFile(userId: String): File = File(userDirectory(userId), "albums.json")

        private fun albumPhotosIndexFile(
            userId: String,
            albumUid: String,
        ): File = File(albumPhotosDirectory(userId), "${safeName(albumUid)}.json")

        private fun albumPhotosDirectory(userId: String): File = File(userDirectory(userId), "album-photos")

        private fun syncMetadataFile(
            userId: String,
            source: String,
        ): File = File(File(userDirectory(userId), "sync"), "${safeName(source)}.timestamp")

        private fun thumbnailQueueFile(userId: String): File = File(userDirectory(userId), "thumbnail-queue.json")

        private fun userDirectory(userId: String): File = File(root, safeName(userId))

        private fun readPhotoIndex(
            userId: String,
            index: File,
        ): List<ProtonGalleryPhoto> {
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
                            ),
                        )
                    }
                }
            }.getOrElse {
                index.delete()
                emptyList()
            }
        }

        private fun writePhotoIndex(
            userId: String,
            target: File,
            photos: List<ProtonGalleryPhoto>,
        ) {
            val array = JSONArray()
            photos.forEach { photo ->
                array.put(
                    JSONObject()
                        .put("nodeUid", photo.nodeUid)
                        .put("captureTime", photo.captureTimeEpochSeconds),
                )
            }
            writeAtomically(userId, target, array.toString(), "Could not commit Proton Photos index")
        }

        private fun nonTimelineReferencedNodeUids(userId: String): Set<String> =
            buildSet {
                readAlbums(userId).mapNotNullTo(this) { it.coverPhotoNodeUid }
                albumPhotosDirectory(userId)
                    .listFiles()
                    ?.filter { it.extension == "json" }
                    ?.forEach { file -> readPhotoIndex(userId, file).mapTo(this, ProtonGalleryPhoto::nodeUid) }
            }

        private fun writeAtomically(
            userId: String,
            target: File,
            contents: String,
            failureMessage: String,
        ) {
            secureFiles.writeText(scope(userId), target, contents, failureMessage)
        }

        private fun hasValidArray(
            userId: String,
            file: File,
        ): Boolean {
            if (!file.isFile) return false
            return runCatching {
                JSONArray(readText(userId, file))
                true
            }.getOrElse {
                file.delete()
                false
            }
        }

        private fun readText(
            userId: String,
            file: File,
        ): String = secureFiles.readText(scope(userId), file)

        private fun scope(userId: String): String = ProtonStorageLayout.mediaScope(userId)

        /** Stale partial downloads and completed files that no longer belong to a known node. */
        private fun File.isPrunable(validNames: Set<String>): Boolean =
            isStalePartial(this) || (extension != "part" && nameWithoutExtension !in validNames)

        private fun isStalePartial(file: File): Boolean =
            file.extension == "part" && isExpired(file, ProtonStorageLayout.STALE_PART_TTL_MILLIS)

        private fun isExpired(
            file: File,
            ttlMillis: Long,
        ): Boolean = file.lastModified() <= 0L || clock.nowMillis() - file.lastModified() > ttlMillis

        private fun expireFiles(
            directory: File,
            ttlMillis: Long,
        ) {
            directory
                .listFiles()
                ?.filter { file -> file.isFile && isExpired(file, ttlMillis) }
                ?.forEach(File::delete)
        }

        private fun safeName(value: String): String = AtomicFileStore.safeName(value)
    }
