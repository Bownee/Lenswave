package com.bownee.lenswave.proton

import android.content.Context
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
        private val previews: ProtonPreviewStore,
        private val originals: ProtonOriginalStore,
    ) : ProtonSyncMetadataStore,
        ProtonAccountCacheCleaner,
        ProtonTimelineCache,
        ProtonAlbumCache,
        ProtonMediaCache,
        ProtonSessionCache,
        ProtonThumbnailQueueStore {
        private val root = File(context.filesDir, ProtonStorageLayout.METADATA_DIRECTORY).apply { mkdirs() }

        /**
         * One rendition listing per user, shared by every snapshot read until a rendition is
         * written, removed or swept. A cold start hydrates the timeline, the tags and the albums
         * and each read used to list both directories and hash every name into a set on its own.
         */
        private val renditions = ConcurrentHashMap<String, ProtonStoredRenditions>()

        /** Metadata hydration only needs availability; authenticated contents are validated when read. */
        override fun thumbnailExists(
            userId: String,
            nodeUid: String,
        ): Boolean = thumbnails.exists(userId, nodeUid)

        override fun loadThumbnail(
            userId: String,
            nodeUid: String,
            isActive: () -> Boolean,
        ) = thumbnails.load(userId, nodeUid, isActive)

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
            forgetRenditions(userId)
        }

        override fun removeThumbnail(
            userId: String,
            nodeUid: String,
        ) {
            thumbnails.remove(userId, nodeUid)
            forgetRenditions(userId)
        }

        override fun thumbnailCount(userId: String): Int = thumbnails.count(userId)

        override fun readThumbnailBytes(
            userId: String,
            nodeUid: String,
        ): ByteArray? = thumbnails.readBytes(userId, nodeUid)

        override fun previewExists(
            userId: String,
            nodeUid: String,
        ): Boolean = previews.exists(userId, nodeUid)

        override fun writePreview(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            previews.write(userId, nodeUid, bytes)
            forgetRenditions(userId)
        }

        override fun loadPreview(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ) = previews.load(userId, nodeUid, targetLongEdge)

        override fun removePreview(
            userId: String,
            nodeUid: String,
        ) {
            previews.remove(userId, nodeUid)
            forgetRenditions(userId)
        }

        override fun previewCount(userId: String): Int = previews.count(userId)

        /**
         * The listing runs under the map's lock, so a write that lands while it runs waits and
         * then drops the result rather than leaving a listing that predates it memoized.
         */
        override fun storedRenditions(userId: String): ProtonStoredRenditions =
            renditions.computeIfAbsent(userId) {
                ProtonStoredRenditions(thumbnails.storedNames(userId), previews.storedNames(userId))
            }

        private fun forgetRenditions(userId: String) {
            renditions.remove(userId)
        }

        override fun readTimelineSnapshot(
            userId: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? = readPhotoSnapshot(userId, indexFile(userId), availability)

        override fun writeIndex(
            userId: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            writePhotoIndex(userId, indexFile(userId), photos)
        }

        override fun readTagSnapshot(
            userId: String,
            tag: ProtonMediaTag,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? = readPhotoSnapshot(userId, tagIndexFile(userId, tag), availability)

        override fun writeTag(
            userId: String,
            tag: ProtonMediaTag,
            photos: List<ProtonGalleryPhoto>,
        ) {
            writePhotoIndex(userId, tagIndexFile(userId, tag), photos)
        }

        override fun readAlbumsSnapshot(
            userId: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonAlbum>? =
            readSnapshot(userId, albumsIndexFile(userId)) { text ->
                val array = JSONArray(text)
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
                                hasCoverThumbnail = coverPhotoNodeUid?.let(availability::hasThumbnail) == true,
                                isShared = value.optBoolean("isShared"),
                            ),
                        )
                    }
                }
            }

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

        override fun readAlbumPhotosSnapshot(
            userId: String,
            albumUid: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? = readPhotoSnapshot(userId, albumPhotosIndexFile(userId, albumUid), availability)

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
        ): Long {
            // Freshness is checked on every sync; a missing file is the common case on a new
            // account and is not worth an exception.
            val file = syncMetadataFile(userId, source)
            if (!file.isFile) return 0L
            return runCatching { readText(userId, file).toLong() }.getOrDefault(0L)
        }

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

        override fun readQueue(
            userId: String,
            queue: ProtonQueueName,
        ): List<ProtonThumbnailQueueEntry> =
            readSnapshot(userId, queueFile(userId, queue)) { text ->
                val array = JSONArray(text)
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
            }.orEmpty()

        override fun writeQueue(
            userId: String,
            queue: ProtonQueueName,
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
                queueFile(userId, queue),
                array.toString(),
                "Could not commit Proton download queue",
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
        ): File? = originals.read(userId, nodeUid)

        override fun createOriginalTarget(
            userId: String,
            nodeUid: String,
        ): Pair<File, File> = originals.createTarget(userId, nodeUid)

        override fun commitOriginal(
            userId: String,
            nodeUid: String,
            plaintext: File,
            target: File,
        ): File = originals.commit(userId, nodeUid, plaintext, target)

        override fun onOriginalStored(
            userId: String,
            target: File,
        ) {
            originals.onStored(userId, target)
        }

        override fun prepareUser(userId: String) {
            originals.wipeStaleDecryptedCopies()
        }

        override fun trimUser(userId: String) {
            thumbnails.maintain(userId)
            previews.maintain(userId)
            originals.maintain(userId)
            forgetRenditions(userId)
        }

        override fun reconcilePhotos(
            userId: String,
            cachedNodeUids: Collection<String>,
            remoteNodeUids: Collection<String>,
        ) {
            val changes = ProtonPhotoReconciliation.compare(cachedNodeUids, remoteNodeUids)
            val remote = remoteNodeUids.toSet()
            val availability = storedRenditions(userId)
            ProtonMediaTag.entries.forEach { tag ->
                val tagged = readTagSnapshot(userId, tag, availability) ?: return@forEach
                val retained = tagged.filter { it.nodeUid in remote }
                if (retained.size != tagged.size) writeTag(userId, tag, retained)
            }
            val retainedNodeUids = nonTimelineReferencedNodeUids(userId)
            changes.removedNodeUids.forEach { nodeUid ->
                if (nodeUid in retainedNodeUids) return@forEach
                thumbnails.remove(userId, nodeUid)
                previews.remove(userId, nodeUid)
                originals.remove(userId, nodeUid)
            }
            // The three stores judge their files against one shared set of retained file names
            // rather than each hashing every referenced node uid on its own.
            val referencedNames = HashSet<String>((remoteNodeUids.size + retainedNodeUids.size) * 4 / 3 + 1)
            remoteNodeUids.mapTo(referencedNames, ::safeName)
            retainedNodeUids.mapTo(referencedNames, ::safeName)
            thumbnails.removeUnreferenced(userId, referencedNames)
            previews.removeUnreferenced(userId, referencedNames)
            originals.removeUnreferenced(userId, referencedNames)
            forgetRenditions(userId)
        }

        override fun removePhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
            val removed = nodeUids.toSet()
            removed.forEach { nodeUid ->
                thumbnails.remove(userId, nodeUid)
                previews.remove(userId, nodeUid)
                originals.remove(userId, nodeUid)
            }
            forgetRenditions(userId)
            val availability = storedRenditions(userId)
            // A listing that did not contain any of the photos is left as it is; rewriting it
            // would encrypt and commit the same contents again under the sync mutex.
            readTimelineSnapshot(userId, availability)?.let { photos ->
                val remaining = photos.filterNot { it.nodeUid in removed }
                if (remaining.size != photos.size) writeIndex(userId, remaining)
            }
            ProtonMediaTag.entries.forEach { tag ->
                readTagSnapshot(userId, tag, availability)?.let { photos ->
                    val remaining = photos.filterNot { it.nodeUid in removed }
                    if (remaining.size != photos.size) writeTag(userId, tag, remaining)
                }
            }
            // Only albums that actually lost a photo are rewritten; the rest keep their counts.
            val albumCounts = mutableMapOf<String, Long>()
            albumPhotosDirectory(userId)
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.forEach { file ->
                    val photos = readPhotoSnapshot(userId, file, availability) ?: return@forEach
                    val remaining = photos.filterNot { it.nodeUid in removed }
                    if (remaining.size == photos.size) return@forEach
                    writePhotoIndex(userId, file, remaining)
                    albumCounts[file.nameWithoutExtension] = remaining.size.toLong()
                }
            if (albumCounts.isNotEmpty()) {
                readAlbumsSnapshot(userId, availability)?.let { albums ->
                    writeAlbums(
                        userId,
                        albums.map { album ->
                            albumCounts[safeName(album.nodeUid)]?.let { count -> album.copy(photoCount = count) }
                                ?: album
                        },
                    )
                }
            }
        }

        /**
         * Best effort: a file that cannot be deleted right now is reported and left for
         * [retainOnlyUser] to sweep on the next account transition. Throwing here would fail the
         * session teardown, and the session manager retries a failed transition forever while
         * showing the account as transitioning. The data key is deleted regardless, so whatever
         * residue survives is unreadable and is discarded as corrupt by the next read.
         */
        override fun clearUser(userId: String) {
            thumbnails.clearMemory(userId)
            previews.forget(userId)
            forgetRenditions(userId)
            val indexDeleted = userDirectory(userId).deleteRecursively()
            val originalsDeleted = originals.clear(userId)
            if (!indexDeleted || !originalsDeleted) {
                LenswaveDiagnostics.reportFailure(
                    LenswaveOperation.CACHE_CLEAR,
                    IllegalStateException("Could not remove all cached Proton media; residue is swept later"),
                )
            }
            secureFiles.deleteKey(scope(userId))
        }

        override fun retainOnlyUser(userId: String?) {
            val retainedName = userId?.let(::safeName)
            root.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
                check(directory.deleteRecursively()) { "Could not remove orphaned Proton cache" }
            }
            originals.retainOnly(userId)
            thumbnails.retainMemoryFor(userId)
            previews.retainCountsFor(userId)
            renditions.keys.removeAll { key -> key != userId }
        }

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

        private fun queueFile(
            userId: String,
            queue: ProtonQueueName,
        ): File = File(userDirectory(userId), queue.fileName)

        private fun userDirectory(userId: String): File = File(root, safeName(userId))

        /** The listing in [index], or null when it is absent or unreadable; see [readSnapshot]. */
        private fun readPhotoSnapshot(
            userId: String,
            index: File,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? =
            parsePhotoIndex(userId, index) { value ->
                availability.photo(value.getString("nodeUid"), value.getLong("captureTime"))
            }

        /** Node uids only, for callers that never look at rendition availability. */
        private fun readNodeUids(
            userId: String,
            index: File,
        ): List<String> = parsePhotoIndex(userId, index) { value -> value.getString("nodeUid") }.orEmpty()

        private inline fun <T> parsePhotoIndex(
            userId: String,
            index: File,
            entry: (JSONObject) -> T,
        ): List<T>? =
            readSnapshot(userId, index) { text ->
                val array = JSONArray(text)
                List(array.length()) { position -> entry(array.getJSONObject(position)) }
            }

        /**
         * [file] read, decrypted and parsed, or null when it is absent or unreadable. A corrupt
         * file is deleted so it reads as a plain miss from then on; a crypto or I/O failure (a
         * Keystore that refuses to unwrap the data key for a moment, say) is reported and leaves
         * the file alone, so one hiccup cannot wipe the timeline, the albums or the download queue.
         */
        private inline fun <T> readSnapshot(
            userId: String,
            file: File,
            parse: (String) -> T,
        ): T? {
            if (!file.isFile) return null
            return try {
                parse(readText(userId, file))
            } catch (error: Exception) {
                if (ProtonSnapshotCorruptionPolicy.isCorrupt(error)) {
                    file.delete()
                } else {
                    LenswaveDiagnostics.reportFailure(LenswaveOperation.CACHE_SNAPSHOT_READ, error)
                }
                null
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
                readAlbumsSnapshot(userId, ProtonStoredRenditions.NONE)
                    ?.mapNotNullTo(this) { it.coverPhotoNodeUid }
                albumPhotosDirectory(userId)
                    .listFiles()
                    ?.filter { it.extension == "json" }
                    ?.forEach { file -> addAll(readNodeUids(userId, file)) }
            }

        private fun writeAtomically(
            userId: String,
            target: File,
            contents: String,
            failureMessage: String,
        ) {
            secureFiles.writeText(scope(userId), target, contents, failureMessage)
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

        private fun safeName(value: String): String = AtomicFileStore.safeName(value)
    }
