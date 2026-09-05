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

        /** Queues whose file could not be read for a transient reason; see [readQueue] and [writeQueue]. */
        private val unreadQueues: MutableSet<Pair<String, ProtonQueueName>> = ConcurrentHashMap.newKeySet()

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

        /**
         * The queue hydrates as empty when its file cannot be read right now, since the store
         * contract has no "unknown"; the queue is remembered in [unreadQueues] so the next
         * [writeQueue] merges rather than replaces the file, which still holds the good copy.
         */
        override fun readQueue(
            userId: String,
            queue: ProtonQueueName,
        ): List<ProtonThumbnailQueueEntry> {
            val key = userId to queue
            unreadQueues -= key
            return readSnapshot(userId, queueFile(userId, queue), ::parseQueue) { error ->
                LenswaveDiagnostics.reportFailure(LenswaveOperation.CACHE_SNAPSHOT_READ, error)
                unreadQueues += key
                null
            }.orEmpty()
        }

        private fun parseQueue(text: String): List<ProtonThumbnailQueueEntry> {
            val array = JSONArray(text)
            return buildList {
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
                            networkRetryCount = value.optInt("networkRetryCount"),
                        ),
                    )
                }
            }
        }

        override fun writeQueue(
            userId: String,
            queue: ProtonQueueName,
            entries: List<ProtonThumbnailQueueEntry>,
        ) {
            val key = userId to queue
            val written = if (key in unreadQueues) mergeWithStoredQueue(userId, queue, entries) else entries
            val array = JSONArray()
            written.forEach { entry ->
                val sourceCaptureTimes = JSONObject()
                entry.sourceCaptureTimes.toSortedMap().forEach { (source, captureTime) ->
                    sourceCaptureTimes.put(source, captureTime)
                }
                array.put(
                    JSONObject()
                        .put("nodeUid", entry.nodeUid)
                        .put("sourceCaptureTimes", sourceCaptureTimes)
                        .put("retryCount", entry.retryCount)
                        .put("retryAtMillis", entry.retryAtMillis)
                        .put("networkRetryCount", entry.networkRetryCount),
                )
            }
            writeAtomically(
                userId,
                queueFile(userId, queue),
                array.toString(),
                "Could not commit Proton download queue",
            )
            unreadQueues -= key
        }

        /**
         * The in-memory queue started empty because its file could not be read (see
         * [readQueue]), so writing it as it is would throw away every entry the file still
         * holds. The file is read again here: its entries that memory does not know are kept
         * and memory wins for the rest. A file that still cannot be read refuses the write;
         * the queue reports that and retries the flush with backoff, by which time the
         * Keystore or the disk has usually recovered.
         */
        private fun mergeWithStoredQueue(
            userId: String,
            queue: ProtonQueueName,
            entries: List<ProtonThumbnailQueueEntry>,
        ): List<ProtonThumbnailQueueEntry> {
            val stored =
                readSnapshot(userId, queueFile(userId, queue), ::parseQueue) { error ->
                    throw IllegalStateException("Could not read the Proton download queue before writing it", error)
                }.orEmpty()
            return ProtonQueueMergePolicy.merge(entries, stored)
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
            shouldContinue: () -> Boolean,
        ): File? = originals.read(userId, nodeUid, shouldContinue)

        override fun createOriginalTarget(
            userId: String,
            nodeUid: String,
        ): ProtonOriginalTarget = originals.createTarget(userId, nodeUid)

        override fun commitOriginal(
            userId: String,
            nodeUid: String,
            download: ProtonOriginalTarget,
        ): ProtonOriginalCommit = originals.commit(userId, nodeUid, download)

        override fun onOriginalStored(
            userId: String,
            target: File,
        ) {
            originals.onStored(userId, target)
        }

        override fun trimUser(userId: String) {
            thumbnails.maintain(userId)
            previews.maintain(userId)
            originals.maintain(userId)
            sweepStaleWriteTemporaries(userId)
            removeUnreferencedRenditions(userId)
            forgetRenditions(userId)
        }

        /**
         * Deletes every stored rendition and original no listing names: a crash between a
         * listing write and its rendition deletes leaves them behind, and so did every reconcile
         * that found nothing changed. Once per activation is enough for strays; a reconcile
         * deletes what it removed itself. Skipped whenever a listing could not be read right now,
         * since a listing that reads as empty would otherwise delete everything it references.
         */
        private fun removeUnreferencedRenditions(userId: String) {
            val timelineNodeUids = readNodeUidsOrNull(userId, indexFile(userId)) ?: return
            val otherNodeUids = nonTimelineReferencedNodeUidsOrNull(userId) ?: return
            val referencedNames = HashSet<String>((timelineNodeUids.size + otherNodeUids.size) * 4 / 3 + 1)
            timelineNodeUids.mapTo(referencedNames, ::safeName)
            otherNodeUids.mapTo(referencedNames, ::safeName)
            thumbnails.removeUnreferenced(userId, referencedNames)
            previews.removeUnreferenced(userId, referencedNames)
            originals.removeUnreferenced(userId, referencedNames)
        }

        override fun sweepExpiredDecryptedCopies() {
            originals.sweepExpiredDecryptedCopies()
        }

        /**
         * An atomic write leaves its `.part` file beside the target when the process dies between
         * the write and the rename, and nothing else ever removes it. Only the directories the
         * listings, the tag files, the sync stamps and the queues are written to are swept (the
         * album-photo indexes are swept by [reconcileAlbums]), and only files past the stale TTL,
         * so a write in progress on another thread is left alone.
         */
        private fun sweepStaleWriteTemporaries(userId: String) {
            val user = userDirectory(userId)
            listOf(user, File(user, TAGS_DIRECTORY), File(user, SYNC_DIRECTORY)).forEach { directory ->
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && isStalePartial(file)) file.delete()
                }
            }
        }

        override fun reconcilePhotos(
            userId: String,
            cachedNodeUids: Collection<String>,
            remoteNodeUids: Collection<String>,
        ) {
            val remote = remoteNodeUids.toHashSet()
            val changes = ProtonPhotoReconciliation.compare(cachedNodeUids, remote)
            // An unchanged library has no tag entry to drop and no rendition to delete, and
            // this used to decrypt every tag file, the albums index and every album-photo index
            // on each sync to find that out. Stale write temporaries and renditions no listing
            // names are swept by [trimUser], once per activation rather than per reconcile.
            if (changes.isEmpty) return
            ProtonMediaTag.entries.forEach { tag ->
                val tagged = readPhotoEntries(userId, tagIndexFile(userId, tag)) ?: return@forEach
                val retained = tagged.filter { it.nodeUid in remote }
                if (retained.size != tagged.size) writeTag(userId, tag, retained)
            }
            // An album-only photo keeps its renditions. When the album listings cannot be read right
            // now, nothing is deleted: a listing that read as empty used to delete every album-only
            // photo, and the strays a skipped sweep leaves are [trimUser]'s job.
            val referencedElsewhere = nonTimelineReferencedNodeUidsOrNull(userId)
            if (referencedElsewhere == null) {
                LenswaveDiagnostics.reportFailure(
                    LenswaveOperation.CACHE_SNAPSHOT_READ,
                    ProtonRenditionSweepSkippedException(),
                )
            }
            ProtonReconcileDeletionPolicy.deletable(changes.removedNodeUids, referencedElsewhere).forEach { nodeUid ->
                thumbnails.remove(userId, nodeUid)
                previews.remove(userId, nodeUid)
                originals.remove(userId, nodeUid)
            }
            forgetRenditions(userId)
        }

        override fun removePhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
            val removed = nodeUids.toSet()
            // The listings are rewritten before any rendition is deleted, so a crash in between
            // leaves stray files for the next reconcile rather than listings that still name
            // the photos. Only the node uids and capture times are persisted, so the listings
            // are parsed without hydrating rendition availability. The album indexes are
            // [removeAlbumPhotos]' job, under the album repository's lock.
            // A listing that did not contain any of the photos is left as it is; rewriting it
            // would encrypt and commit the same contents again under the mutation mutex.
            readPhotoEntries(userId, indexFile(userId))?.let { photos ->
                val remaining = photos.filterNot { it.nodeUid in removed }
                if (remaining.size != photos.size) writeIndex(userId, remaining)
            }
            ProtonMediaTag.entries.forEach { tag ->
                readPhotoEntries(userId, tagIndexFile(userId, tag))?.let { photos ->
                    val remaining = photos.filterNot { it.nodeUid in removed }
                    if (remaining.size != photos.size) writeTag(userId, tag, remaining)
                }
            }
            removed.forEach { nodeUid ->
                thumbnails.remove(userId, nodeUid)
                previews.remove(userId, nodeUid)
                originals.remove(userId, nodeUid)
            }
            forgetRenditions(userId)
        }

        /**
         * The album repository calls this under its own mutation lock, so an album-photo sync
         * committing at the same time cannot overwrite the rewritten index with a listing that
         * still names the photos.
         */
        override fun removeAlbumPhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
            val removed = nodeUids.toSet()
            // Only albums that actually lost a photo are rewritten; the rest keep their counts.
            val albumCounts = mutableMapOf<String, Long>()
            albumPhotosDirectory(userId)
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.forEach { file ->
                    val photos = readPhotoEntries(userId, file) ?: return@forEach
                    val remaining = photos.filterNot { it.nodeUid in removed }
                    if (remaining.size == photos.size) return@forEach
                    writePhotoIndex(userId, file, remaining)
                    albumCounts[file.nameWithoutExtension] = remaining.size.toLong()
                }
            if (albumCounts.isNotEmpty()) {
                readAlbumsSnapshot(userId, ProtonStoredRenditions.NONE)?.let { albums ->
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
            unreadQueues.removeAll { (owner, _) -> owner == userId }
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

        /**
         * Directory names are hashed user ids and key files are hashed scopes, so an orphaned
         * directory cannot name its key; the alias marker each directory carries does. A directory
         * from before the marker existed leaves its key behind: a wrapped key nothing reads.
         *
         * Best effort, like [clearUser]: this runs inside the account transition, which the
         * session manager retries forever while the gallery shows the account as transitioning,
         * so a directory that resists deletion is reported once and left for the next sweep. Its
         * key is deleted regardless, so whatever residue survives is unreadable.
         */
        override fun retainOnlyUser(userId: String?) {
            val retainedName = userId?.let(::safeName)
            var everythingDeleted = true
            root.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
                val keyAlias = File(directory, KEY_ALIAS_FILE).takeIf(File::isFile)?.readText()
                if (!directory.deleteRecursively()) everythingDeleted = false
                keyAlias?.let { alias ->
                    try {
                        secureFiles.deleteKeyAlias(alias)
                    } catch (error: IllegalArgumentException) {
                        LenswaveDiagnostics.reportFailure(LenswaveOperation.CACHE_CLEAR, error)
                    }
                }
            }
            if (!everythingDeleted) {
                LenswaveDiagnostics.reportFailure(
                    LenswaveOperation.CACHE_CLEAR,
                    IllegalStateException("Could not remove all orphaned Proton caches; residue is swept later"),
                )
            }
            // A user who only ever cached originals has no metadata directory and so no alias
            // marker; the key family recorded beside each wrapped key finds that key anyway.
            val retainedAlias = userId?.let { secureFiles.keyAlias(scope(it)) }
            secureFiles.keyAliases(MEDIA_KEY_FAMILY).filter { alias -> alias != retainedAlias }.forEach { alias ->
                try {
                    secureFiles.deleteKeyAlias(alias)
                } catch (error: IllegalArgumentException) {
                    LenswaveDiagnostics.reportFailure(LenswaveOperation.CACHE_CLEAR, error)
                }
            }
            originals.retainOnly(userId)
            thumbnails.retainMemoryFor(userId)
            previews.retainCountsFor(userId)
            renditions.keys.removeAll { key -> key != userId }
            unreadQueues.removeAll { (owner, _) -> owner != userId }
        }

        private fun indexFile(userId: String): File = File(userDirectory(userId), "index.json")

        private fun tagIndexFile(
            userId: String,
            tag: ProtonMediaTag,
        ): File = File(File(userDirectory(userId), TAGS_DIRECTORY), "${tag.name.lowercase()}.json")

        private fun albumsIndexFile(userId: String): File = File(userDirectory(userId), "albums.json")

        private fun albumPhotosIndexFile(
            userId: String,
            albumUid: String,
        ): File = File(albumPhotosDirectory(userId), "${safeName(albumUid)}.json")

        private fun albumPhotosDirectory(userId: String): File = File(userDirectory(userId), "album-photos")

        private fun syncMetadataFile(
            userId: String,
            source: String,
        ): File = File(File(userDirectory(userId), SYNC_DIRECTORY), "${safeName(source)}.timestamp")

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

        /**
         * The persisted fields only, for listings that are rewritten rather than shown: hydrating
         * availability hashes every node uid, and a reconcile or a removal never looks at it.
         */
        private fun readPhotoEntries(
            userId: String,
            index: File,
        ): List<ProtonGalleryPhoto>? =
            parsePhotoIndex(userId, index) { value ->
                ProtonGalleryPhoto(
                    nodeUid = value.getString("nodeUid"),
                    captureTimeEpochSeconds = value.getLong("captureTime"),
                    hasThumbnail = false,
                )
            }

        /** Node uids only, for callers that never look at rendition availability; null when the listing is absent or unreadable. */
        private fun readNodeUidsOrNull(
            userId: String,
            index: File,
        ): List<String>? = parsePhotoIndex(userId, index) { value -> value.getString("nodeUid") }

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
        ): T? =
            readSnapshot(userId, file, parse) { error ->
                LenswaveDiagnostics.reportFailure(LenswaveOperation.CACHE_SNAPSHOT_READ, error)
                null
            }

        /** [readSnapshot] with the transient case in the caller's hands: [onTransientFailure] decides what it reads as. */
        private inline fun <T> readSnapshot(
            userId: String,
            file: File,
            parse: (String) -> T,
            onTransientFailure: (Exception) -> T?,
        ): T? {
            if (!file.isFile) return null
            return try {
                parse(readText(userId, file))
            } catch (error: Exception) {
                if (ProtonSnapshotCorruptionPolicy.isCorrupt(error)) {
                    file.delete()
                    null
                } else {
                    onTransientFailure(error)
                }
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

        /**
         * Every node uid the albums index, the tag files and the album-photo indexes name, or
         * null when any listing that exists cannot be read right now: a sweep judged against a
         * partial set would delete renditions a listing still references.
         */
        private fun nonTimelineReferencedNodeUidsOrNull(userId: String): Set<String>? {
            val referenced = HashSet<String>()
            if (albumsIndexFile(userId).isFile) {
                val albums = readAlbumsSnapshot(userId, ProtonStoredRenditions.NONE) ?: return null
                albums.mapNotNullTo(referenced) { it.coverPhotoNodeUid }
            }
            ProtonMediaTag.entries.forEach { tag ->
                val file = tagIndexFile(userId, tag)
                if (file.isFile) referenced.addAll(readNodeUidsOrNull(userId, file) ?: return null)
            }
            albumPhotosDirectory(userId)
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.forEach { file -> referenced.addAll(readNodeUidsOrNull(userId, file) ?: return null) }
            return referenced
        }

        private fun writeAtomically(
            userId: String,
            target: File,
            contents: String,
            failureMessage: String,
        ) {
            recordKeyAlias(userId)
            secureFiles.writeText(scope(userId), target, contents, failureMessage)
        }

        /** Leaves the data-key alias beside the user's metadata so [retainOnlyUser] can delete the key. */
        private fun recordKeyAlias(userId: String) {
            val marker = File(userDirectory(userId), KEY_ALIAS_FILE)
            if (marker.isFile) return
            AtomicFileStore.write(marker, secureFiles.keyAlias(scope(userId)), "Could not record the cache key alias")
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

        private companion object {
            const val KEY_ALIAS_FILE = "key-alias"

            /** The scope family of every media key: [ProtonStorageLayout.mediaScope] before the user id. */
            val MEDIA_KEY_FAMILY = ProtonStorageLayout.mediaScope("").substringBefore(':')
            const val TAGS_DIRECTORY = "tags"
            const val SYNC_DIRECTORY = "sync"
        }
    }
