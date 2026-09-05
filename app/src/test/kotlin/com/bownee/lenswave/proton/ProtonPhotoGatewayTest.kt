package com.bownee.lenswave.proton

import android.graphics.Bitmap
import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProgressUpdate
import me.proton.drive.sdk.ProtonPhotosClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

/**
 * Drives the real gateway over the real repositories, queues and session guard; only the disk
 * (the photo cache and the queue store), the clock and the SDK client are faked. Housekeeping is
 * launched into the test scheduler so a test decides when it runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonPhotoGatewayTest {
    private val testScope = TestScope()
    private val clock = FakeClock()
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val store = FakeQueueStore()
    private val cache = FakeCache(events, store)
    private val clients = FakeClientProvider(events)
    private val syncFailures = Collections.synchronizedList(mutableListOf<Throwable>())
    private val thumbnailQueue =
        ProtonThumbnailQueue(store, clock, ProtonQueueName.THUMBNAILS, testScope.backgroundScope)
    private val previewQueue = ProtonThumbnailQueue(store, clock, ProtonQueueName.PREVIEWS, testScope.backgroundScope)
    private val snapshotSync =
        ProtonSnapshotSync(ProtonSnapshotCoordinator(FreshMetadata(clock), clock)) { _, error ->
            syncFailures += error
        }
    private val tagListings = FakeTagListings(events)
    private val timeline = ProtonTimelineRepository(clients, cache, snapshotSync, tagListings)
    private val albums = ProtonAlbumRepository(clients, cache, snapshotSync)
    private val transfers = ProtonTransferCoordinator()
    private val renditions = ProtonRenditionDownloads(clients, cache, transfers, ProtonPreviewAdmission())
    private val materializer = FakeMaterializer()
    private val originals =
        ProtonOriginalDownloads(clients, cache, transfers, materializer, ProtonDecryptedCopyRegistry())
    private val sessionGuard = ProtonSessionGuard()

    init {
        // Account A: one photo with every rendition, one without a thumbnail, one video without
        // a preview and one ("x") that also fronts an album and sits in that album's listing.
        cache.timelines[USER_A.id] = listOf(photo("a1", 100L), photo("a2", 200L), photo("a3", 300L), photo("x", 500L))
        cache.tags[USER_A.id to ProtonMediaTag.VIDEOS] = listOf(photo("a3", 300L))
        cache.albums[USER_A.id] = listOf(album("al1", coverPhotoNodeUid = "x"), album("al2", coverPhotoNodeUid = "c1"))
        cache.albumPhotos[USER_A.id to "al1"] = listOf(photo("x", 700L), photo("y", 600L))
        cache.thumbnails += listOf("a1", "a3", "x")
        cache.previews += listOf("a1")
        cache.timelines[USER_B.id] = listOf(photo("b1", 10L))
        cache.albums[USER_B.id] = emptyList()
    }

    @Test
    fun `activation publishes the cached listings and housekeeps afterwards`() =
        testScope.runTest {
            val gateway = gateway()

            gateway.activate(USER_A)

            // The transition reads the listings and nothing else; the wipe of stale plaintext
            // copies is the original store's and the trim is housekeeping.
            assertEquals(listOf("readTimeline:a", "readAlbums:a"), events.toList())
            assertTrue(timeline.state.value.hasLoaded)
            assertEquals(4, timeline.state.value.photos.size)
            assertTrue(albums.albumsState.value.hasLoaded)
            assertEquals(0, thumbnailQueue.pendingCount(USER_A.id))
            runCurrent()
            assertEquals("trimUser:a", events.last())
            assertEquals(mapOf("a2" to setOf(TIMELINE), "c1" to setOf(ALBUM_COVERS)), thumbnailQueue.pendingByNode())
            // a1 has a preview, a3 is a video and the viewer never previews those.
            assertEquals(
                mapOf("a2" to setOf(TIMELINE_PREVIEWS), "x" to setOf(TIMELINE_PREVIEWS)),
                previewQueue.pendingByNode(),
            )

            events.clear()
            gateway.activate(USER_A)
            runCurrent()

            assertTrue("an activation the guard skipped must not housekeep again: $events", events.isEmpty())
        }

    @Test
    fun `switching accounts forgets the previous queues and clears its cache before the next listings load`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            events.clear()
            cache.onClearUser = { userId -> events += queueOrigin(userId) }

            gateway.activate(USER_B)

            assertEquals(
                listOf(
                    "disconnect:a",
                    "queue-rehydrated:a",
                    "clearUser:a",
                    "readTimeline:b",
                    "readAlbums:b",
                ),
                events.toList(),
            )
            assertEquals("b", timeline.state.value.userId)
            runCurrent()
            assertEquals("trimUser:b", events.last())
            assertEquals(mapOf("b1" to setOf(TIMELINE)), thumbnailQueue.pendingByNode())
            assertTrue(syncFailures.isEmpty())
        }

    @Test
    fun `an account switch waits for the housekeeping still in flight`() =
        runBlocking {
            val trimStarted = CompletableDeferred<Unit>()
            val releaseTrim = CountDownLatch(1)
            val trimmedB = CompletableDeferred<Unit>()
            cache.onTrimUser = { userId ->
                when (userId) {
                    USER_A.id -> {
                        trimStarted.complete(Unit)
                        releaseTrim.await()
                    }

                    USER_B.id -> {
                        trimmedB.complete(Unit)
                    }
                }
            }
            val gateway = gateway(CoroutineScope(SupervisorJob() + Dispatchers.Default))

            gateway.activate(USER_A)
            trimStarted.await()
            val switch = async(Dispatchers.Default) { gateway.activate(USER_B) }
            delay(100L)

            assertFalse("the switch must wait for the housekeeping of the previous account", switch.isCompleted)
            assertFalse("readTimeline:b" in events)
            releaseTrim.countDown()
            switch.await()
            trimmedB.await()
            assertTrue(events.indexOf("trimUser:a") < events.indexOf("clearUser:a"))
            assertTrue(events.indexOf("clearUser:a") < events.indexOf("readTimeline:b"))
        }

    @Test
    fun `disconnect forgets the queues before the wipe and resets only the active account's listings`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            events.clear()
            cache.onClearUser = { userId -> events += queueOrigin(userId) }

            gateway.disconnect(USER_A)

            assertEquals(listOf("disconnect:a", "queue-rehydrated:a", "clearUser:a"), events.toList())
            assertEquals(ProtonGalleryState(), timeline.state.value)
            assertEquals(ProtonAlbumsState(), albums.albumsState.value)
            assertFalse(sessionGuard.isActive(USER_A))

            gateway.activate(USER_B)
            runCurrent()
            events.clear()
            gateway.disconnect(USER_A)

            assertEquals(listOf("disconnect:a", "queue-rehydrated:a", "clearUser:a"), events.toList())
            assertEquals("b", timeline.state.value.userId)
            assertTrue(sessionGuard.isActive(USER_B))
        }

    @Test
    fun `a thumbnail the store cannot decode is dropped and queued again from every listing that shows it`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            gateway.loadCachedAlbum(USER_A, ProtonAlbumReference("al1", "Album"))
            assertEquals(setOf(ALBUM_PHOTOS_AL1), thumbnailQueue.pendingByNode()["y"])
            cache.loadThumbnail = { null }
            events.clear()

            assertNull(gateway.loadThumbnail(USER_A, "x"))

            assertEquals(listOf("removeThumbnail:x"), events.toList())
            assertFalse(
                timeline.state.value.photos
                    .single { it.nodeUid == "x" }
                    .hasThumbnail,
            )
            assertFalse(
                albums.albumsState.value.albums
                    .single { it.nodeUid == "al1" }
                    .hasCoverThumbnail,
            )
            assertFalse(
                albums.albumPhotosState.value.photos
                    .single { it.nodeUid == "x" }
                    .hasThumbnail,
            )
            val entry = thumbnailQueue.claimReady(USER_A.id, limit = 10).single { it.nodeUid == "x" }
            assertEquals(setOf(TIMELINE, ALBUM_COVERS, ALBUM_PHOTOS_AL1), entry.sources)
            // The album listing carries the later capture time, and the newest capture time wins.
            assertEquals(700L, entry.captureTimeEpochSeconds)
            assertEquals(0L, entry.retryAtMillis)
        }

    @Test
    fun `a cancelled thumbnail load is not mistaken for a corrupt thumbnail`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            cache.loadThumbnail = { isActive ->
                entered.complete(Unit)
                release.await()
                if (!isActive()) throw CancellationException("the grid moved on")
                null
            }
            events.clear()

            val load = async(Dispatchers.Default) { gateway.loadThumbnail(USER_A, "x") }
            entered.await()
            load.cancel()
            release.countDown()

            assertTrue(runCatching { load.await() }.exceptionOrNull() is CancellationException)
            assertTrue("a cancelled load must not invalidate: $events", events.isEmpty())
            assertTrue(
                timeline.state.value.photos
                    .single { it.nodeUid == "x" }
                    .hasThumbnail,
            )
            assertNull(thumbnailQueue.pendingByNode()["x"])
        }

    @Test
    fun `a preview the store cannot decode is dropped, shown as missing and queued again at the front`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            assertTrue(
                timeline.state.value.photos
                    .single { it.nodeUid == "a1" }
                    .hasPreview,
            )
            assertNull(previewQueue.pendingByNode()["a1"])
            events.clear()

            assertNull(gateway.loadPreview(USER_A, "a1", targetLongEdge = 1_000))

            assertEquals(listOf("removePreview:a1"), events.toList())
            assertFalse(
                timeline.state.value.photos
                    .single { it.nodeUid == "a1" }
                    .hasPreview,
            )
            val entry = previewQueue.claimReady(USER_A.id, limit = 10).single { it.nodeUid == "a1" }
            assertEquals(setOf(TIMELINE_PREVIEWS), entry.sources)
            assertEquals(100L, entry.captureTimeEpochSeconds)
            assertEquals(0L, entry.retryAtMillis)
        }

    @Test
    fun `a photo without a stored preview is not invalidated when its preview load misses`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            previewQueue.replaceSource(USER_A.id, TIMELINE_PREVIEWS, emptyList())
            events.clear()

            assertNull(gateway.loadPreview(USER_A, "a2", targetLongEdge = 1_000))

            assertTrue("a miss must not invalidate: $events", events.isEmpty())
            assertNull(previewQueue.pendingByNode()["a2"])
        }

    @Test
    fun `a preview that cannot be decrypted right now is neither dropped nor queued again`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            cache.loadPreview = { throw ProtonRenditionUnavailableException(IllegalStateException("keystore")) }
            events.clear()

            assertNull(gateway.loadPreview(USER_A, "a1", targetLongEdge = 1_000))

            assertTrue("an unavailable preview must not invalidate: $events", events.isEmpty())
            assertTrue(
                timeline.state.value.photos
                    .single { it.nodeUid == "a1" }
                    .hasPreview,
            )
            assertNull(previewQueue.pendingByNode()["a1"])
        }

    @Test
    fun `prepareCachedOriginal stops the decrypt once the caller loses interest`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            val shouldContinue = AtomicReference<() -> Boolean>()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            cache.readOriginal = { predicate ->
                shouldContinue.set(predicate)
                entered.complete(Unit)
                release.await()
                null
            }

            val prepare = async(Dispatchers.Default) { gateway.prepareCachedOriginal(USER_A, "x") }
            entered.await()

            assertTrue(shouldContinue.get()())
            prepare.cancel()
            assertFalse(shouldContinue.get()())
            release.countDown()
            prepare.join()
        }

    @Test
    fun `a cached original is handed to the player after its first verified segment`() =
        runBlocking {
            val directory = createTempDirectory("lenswave-originals").toFile()
            val firstSegmentSeen = CountDownLatch(1)
            val committed = File(directory, "video.image")
            materializer.materialize = { _, onStarted, onBytesWritten ->
                val part = File(directory, "video.image.part")
                onStarted(part, 1_536L)
                part.writeBytes(ByteArray(1_024))
                onBytesWritten(1_024L)
                // The player already reads while the rest of the file decrypts.
                assertTrue(firstSegmentSeen.await(5L, TimeUnit.SECONDS))
                part.appendBytes(ByteArray(512))
                onBytesWritten(1_536L)
                assertTrue(part.renameTo(committed))
                committed
            }
            lateinit var stream: ProtonOriginalStream
            try {
                val file =
                    withContext(Dispatchers.IO) {
                        originals.downloadOriginalProgressively(USER_A, "a3") { ready ->
                            stream = ready
                            assertEquals(ProtonOriginalReadState(1_024L, complete = false), ready.awaitReadable(0L))
                            // The decrypt of a cached original is sized from the encrypted file.
                            assertEquals(
                                ProtonOriginalDownloadProgress(1_024L, 1_536L, complete = false),
                                ready.progress.value,
                            )
                            firstSegmentSeen.countDown()
                        }
                    }

                assertEquals(committed, file)
                assertEquals(committed, stream.file)
                assertEquals(ProtonOriginalDownloadProgress(1_536L, 1_536L, complete = true), stream.progress.value)
                assertTrue(events.none { it.startsWith("readOriginal") })
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `a downloaded original reaches its end before the commit and moves to its committed path after`() =
        runBlocking {
            val directory = createTempDirectory("lenswave-originals").toFile()
            val part = File(directory, "video.image.part")
            val committed = File(directory, "video.image")
            cache.createOriginalTarget = { ProtonOriginalTarget(part, File(directory, "video.enc"), removalEpoch = 0L) }
            clients.downloadTo = { output -> output.write(ByteBuffer.wrap(ByteArray(1_024))) }
            lateinit var stream: ProtonOriginalStream
            cache.commitOriginal = { download ->
                // The transfer is over: a reader at the end of the bytes is at end-of-input while
                // the file is still the download's own, before the encrypt has read it once more.
                assertEquals(ProtonOriginalReadState(1_024L, complete = true), stream.awaitReadable(1_024L))
                assertTrue(stream.progress.value.complete)
                assertFalse(stream.isComplete)
                assertEquals(part, stream.file)
                assertTrue(download.plaintext.renameTo(committed))
                events += "commitOriginal"
                ProtonOriginalCommit(committed, encryptedStored = true)
            }
            cache.onOriginalStored = { target ->
                // The size accounting runs after the stream has moved to the committed file.
                assertTrue(stream.isComplete)
                assertEquals(committed, stream.file)
                events += "onOriginalStored:${target.name}"
            }
            try {
                val file =
                    withContext(Dispatchers.IO) {
                        originals.downloadOriginalProgressively(USER_A, "a3") { ready -> stream = ready }
                    }

                assertEquals(committed, file)
                assertEquals(listOf("commitOriginal", "onOriginalStored:video.enc"), events.toList())
                assertEquals(ProtonOriginalDownloadProgress(1_024L, 1_024L, complete = true), stream.progress.value)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `a plaintext copy already on disk is handed to the player complete`() =
        runBlocking {
            val copy = File.createTempFile("lenswave-original", ".image").apply { writeBytes(ByteArray(300)) }
            materializer.materialize = { _, _, _ -> copy }
            lateinit var stream: ProtonOriginalStream
            try {
                val file = originals.downloadOriginalProgressively(USER_A, "a3") { ready -> stream = ready }

                assertEquals(copy, file)
                assertEquals(ProtonOriginalReadState(300L, complete = true), stream.awaitReadable(0L))
            } finally {
                copy.delete()
            }
        }

    @Test
    fun `a player that refuses the stream stops the decrypt between segments`() =
        runBlocking {
            val directory = createTempDirectory("lenswave-originals").toFile()
            val refused = CountDownLatch(1)
            materializer.materialize = { shouldContinue, onStarted, onBytesWritten ->
                val part = File(directory, "video.image.part")
                onStarted(part, null)
                part.writeBytes(ByteArray(1_024))
                onBytesWritten(1_024L)
                assertTrue(refused.await(5L, TimeUnit.SECONDS))
                // The decrypt asks before the next segment, as SegmentedEnvelope does.
                while (shouldContinue()) Thread.sleep(5L)
                part.delete()
                throw CancellationException("Copy interrupted before completion")
            }
            try {
                val outcome =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            originals.downloadOriginalProgressively(USER_A, "a3") { ready ->
                                refused.countDown()
                                throw CancellationException("Viewer moved to different media")
                            }
                        }
                    }

                assertTrue(outcome.exceptionOrNull() is CancellationException)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `syncTimelineMetadata reconciles both queues after the sync and refreshes the tag listings`() =
        testScope.runTest {
            val gateway = gateway()
            gateway.activate(USER_A)
            runCurrent()
            // Renditions stored since the last reconciliation, as the background worker reports them.
            timeline.markThumbnailsAvailable(USER_A, setOf("a2"))
            timeline.markPreviewsAvailable(USER_A, setOf("a2"))
            tagListings.listings[ProtonMediaTag.FAVORITES] = listOf(photo("a1", 100L))
            events.clear()

            gateway.syncTimelineMetadata(USER_A, forceRemote = false)

            assertEquals(mapOf("c1" to setOf(ALBUM_COVERS)), thumbnailQueue.pendingByNode())
            assertEquals(mapOf("x" to setOf(TIMELINE_PREVIEWS)), previewQueue.pendingByNode())
            // The cached Videos listing is fresh; Favorites has no snapshot yet, so it is listed.
            assertEquals(listOf("listTag:FAVORITES"), events.filter { it.startsWith("listTag:") })
            val tags = timeline.state.value.tags
            assertEquals(listOf("a3"), tags.getValue(ProtonMediaTag.VIDEOS).photos.map(ProtonGalleryPhoto::nodeUid))
            assertEquals(listOf("a1"), tags.getValue(ProtonMediaTag.FAVORITES).photos.map(ProtonGalleryPhoto::nodeUid))
            assertTrue(tags.getValue(ProtonMediaTag.FAVORITES).hasLoaded)
            assertTrue(syncFailures.isEmpty())
        }

    @Test
    fun `peekThumbnail never asks the store for an inactive account`() =
        testScope.runTest {
            val gateway = gateway()
            cache.peekThumbnail = { error("must not read the store") }

            assertNull(gateway.peekThumbnail(USER_A, "x"))
        }

    private fun gateway(housekeepingScope: CoroutineScope = testScope.backgroundScope) =
        ProtonPhotoGateway(
            timeline = timeline,
            albums = albums,
            originals = originals,
            renditions = renditions,
            renditionSync =
                ProtonRenditionSync(
                    renditions,
                    ProtonRenditionAvailabilityPublisher(timeline, albums),
                    thumbnailQueue,
                    previewQueue,
                    clock,
                ),
            thumbnailQueue = thumbnailQueue,
            previewQueue = previewQueue,
            clientProvider = clients,
            cache = cache,
            renditionStore = cache,
            sessionGuard = sessionGuard,
            housekeepingScope = housekeepingScope,
        )

    /**
     * Whether the thumbnail queue still holds [userId] in memory at this moment. A forgotten
     * queue has to read its file again on the next question, an intact one answers from memory;
     * that read is the only observable difference between "forgotten" and "empty".
     */
    private fun queueOrigin(userId: String): String {
        val readsBefore = store.reads.count { it == userId }
        runBlocking { thumbnailQueue.pendingCount(userId) }
        val rehydrated = store.reads.count { it == userId } > readsBefore
        return if (rehydrated) "queue-rehydrated:$userId" else "queue-in-memory:$userId"
    }

    private suspend fun ProtonThumbnailQueue.pendingByNode(): Map<String, Set<String>> =
        claimReady(USER_A.id, limit = 100)
            .plus(claimReady(USER_B.id, limit = 100))
            .associate { entry -> entry.nodeUid to entry.sources }
            .also {
                releaseAll(USER_A.id)
                releaseAll(USER_B.id)
            }

    private fun photo(
        nodeUid: String,
        captureTime: Long,
    ) = ProtonGalleryPhoto(nodeUid = nodeUid, captureTimeEpochSeconds = captureTime, hasThumbnail = false)

    private fun album(
        nodeUid: String,
        coverPhotoNodeUid: String?,
    ) = ProtonAlbum(
        nodeUid = nodeUid,
        name = nodeUid,
        photoCount = 1L,
        coverPhotoNodeUid = coverPhotoNodeUid,
        createdAtEpochSeconds = 0L,
        lastActivityEpochSeconds = 0L,
        hasCoverThumbnail = false,
        isShared = false,
    )

    /** Every listing counts as synced just now, so no sync ever enumerates unless it has no snapshot. */
    private class FreshMetadata(
        private val clock: FakeClock,
    ) : ProtonSyncMetadataStore {
        override fun readLastSuccessfulSync(
            userId: String,
            source: String,
        ): Long = clock.value

        override fun writeLastSuccessfulSync(
            userId: String,
            source: String,
            timestampMillis: Long,
        ) {
        }
    }

    private class FakeTagListings(
        private val events: MutableList<String>,
    ) : ProtonTagListingClient {
        val listings = mutableMapOf<ProtonMediaTag, List<ProtonGalleryPhoto>>()

        override suspend fun list(
            userId: UserId,
            volumeId: String,
            tag: ProtonMediaTag,
        ): List<ProtonGalleryPhoto> {
            events += "listTag:${tag.name}"
            return listings.getValue(tag)
        }
    }

    private class FakeClientProvider(
        private val events: MutableList<String>,
    ) : ProtonPhotosClientProvider {
        override suspend fun get(userId: UserId): ProtonPhotosClient = error("The SDK must not be reached")

        override suspend fun disconnect(userId: UserId) {
            events += "disconnect:${userId.id}"
        }

        /** Writes the original's bytes into the channel; the SDK is refused unless a test scripts it. */
        var downloadTo: (WritableByteChannel) -> Unit = { error("The SDK must not be reached") }

        override suspend fun downloadTo(
            userId: UserId,
            nodeUid: String,
            output: WritableByteChannel,
            onProgress: (ProgressUpdate) -> Unit,
        ) = downloadTo(output)
    }

    /** The progressive decrypt of a cached original, scripted per test; nothing is cached by default. */
    private class FakeMaterializer : ProtonOriginalMaterializer {
        var materialize: (
            shouldContinue: () -> Boolean,
            onStarted: (File, Long?) -> Unit,
            onBytesWritten: (Long) -> Unit,
        ) -> File? = { _, _, _ -> null }

        override fun materialize(
            userId: String,
            nodeUid: String,
            shouldContinue: () -> Boolean,
            onStarted: (plaintextInProgress: File, expectedBytes: Long?) -> Unit,
            onBytesWritten: (totalBytes: Long) -> Unit,
        ): File? = materialize(shouldContinue, onStarted, onBytesWritten)
    }

    /** The on-disk photo cache: listings keyed by user, renditions as name sets, no bitmaps. */
    private class FakeCache(
        private val events: MutableList<String>,
        private val queueStore: FakeQueueStore,
    ) : ProtonTimelineCache,
        ProtonAlbumCache,
        ProtonMediaCache,
        ProtonSessionCache {
        val timelines = mutableMapOf<String, List<ProtonGalleryPhoto>>()
        val tags = mutableMapOf<Pair<String, ProtonMediaTag>, List<ProtonGalleryPhoto>>()
        val albums = mutableMapOf<String, List<ProtonAlbum>>()
        val albumPhotos = mutableMapOf<Pair<String, String>, List<ProtonGalleryPhoto>>()
        val thumbnails: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val previews: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        var onTrimUser: (String) -> Unit = {}
        var onClearUser: (String) -> Unit = {}
        var loadThumbnail: (isActive: () -> Boolean) -> Bitmap? = { error("no thumbnail load expected") }
        var peekThumbnail: () -> Bitmap? = { null }
        var loadPreview: () -> Bitmap? = { null }
        var readOriginal: (shouldContinue: () -> Boolean) -> File? = { null }
        var createOriginalTarget: () -> ProtonOriginalTarget = { error("no download expected") }
        var commitOriginal: (ProtonOriginalTarget) -> ProtonOriginalCommit = { error("no download expected") }
        var onOriginalStored: (File) -> Unit = {}

        override fun storedRenditions(userId: String): ProtonStoredRenditions =
            ProtonStoredRenditions(thumbnails.toSet(), previews.toSet()) { nodeUid -> nodeUid }

        override fun readTimelineSnapshot(
            userId: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? {
            events += "readTimeline:$userId"
            return timelines[userId]?.hydrated(availability)
        }

        override fun writeIndex(
            userId: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            timelines[userId] = photos
        }

        override fun readTagSnapshot(
            userId: String,
            tag: ProtonMediaTag,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? = tags[userId to tag]?.hydrated(availability)

        override fun writeTag(
            userId: String,
            tag: ProtonMediaTag,
            photos: List<ProtonGalleryPhoto>,
        ) {
            tags[userId to tag] = photos
        }

        override fun thumbnailExists(
            userId: String,
            nodeUid: String,
        ): Boolean = nodeUid in thumbnails

        override fun reconcilePhotos(
            userId: String,
            cachedNodeUids: Collection<String>,
            remoteNodeUids: Collection<String>,
        ) {
        }

        override fun removePhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
        }

        override fun readAlbumsSnapshot(
            userId: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonAlbum>? {
            events += "readAlbums:$userId"
            return albums[userId]?.map { album ->
                album.copy(hasCoverThumbnail = album.coverPhotoNodeUid?.let(availability::hasThumbnail) == true)
            }
        }

        override fun writeAlbums(
            userId: String,
            albums: List<ProtonAlbum>,
        ) {
            this.albums[userId] = albums
        }

        override fun readAlbumPhotosSnapshot(
            userId: String,
            albumUid: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? = albumPhotos[userId to albumUid]?.hydrated(availability)

        override fun writeAlbumPhotos(
            userId: String,
            albumUid: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            albumPhotos[userId to albumUid] = photos
        }

        override fun reconcileAlbums(
            userId: String,
            remoteAlbumUids: Collection<String>,
        ) {
        }

        override fun removeAlbumPhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
        }

        override fun loadThumbnail(
            userId: String,
            nodeUid: String,
            isActive: () -> Boolean,
        ): Bitmap? = loadThumbnail(isActive)

        override fun peekThumbnail(
            userId: String,
            nodeUid: String,
        ): Bitmap? = peekThumbnail()

        override fun writeThumbnail(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            thumbnails += nodeUid
        }

        override fun removeThumbnail(
            userId: String,
            nodeUid: String,
        ) {
            events += "removeThumbnail:$nodeUid"
            thumbnails -= nodeUid
        }

        override fun thumbnailCount(userId: String): Int = thumbnails.size

        override fun readThumbnailBytes(
            userId: String,
            nodeUid: String,
        ): ByteArray? = null

        override fun previewExists(
            userId: String,
            nodeUid: String,
        ): Boolean = nodeUid in previews

        override fun writePreview(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            previews += nodeUid
        }

        override fun loadPreview(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? = loadPreview()

        override fun removePreview(
            userId: String,
            nodeUid: String,
        ) {
            events += "removePreview:$nodeUid"
            previews -= nodeUid
        }

        override fun previewCount(userId: String): Int = previews.size

        override fun readOriginal(
            userId: String,
            nodeUid: String,
            shouldContinue: () -> Boolean,
        ): File? = readOriginal(shouldContinue)

        override fun createOriginalTarget(
            userId: String,
            nodeUid: String,
        ): ProtonOriginalTarget = createOriginalTarget()

        override fun commitOriginal(
            userId: String,
            nodeUid: String,
            download: ProtonOriginalTarget,
        ): ProtonOriginalCommit = commitOriginal(download)

        override fun onOriginalStored(
            userId: String,
            target: File,
        ) {
            onOriginalStored(target)
        }

        override fun clearUser(userId: String) {
            // The real cache erases the user's directory, queue files included.
            queueStore.clear(userId)
            onClearUser(userId)
            events += "clearUser:$userId"
        }

        override fun trimUser(userId: String) {
            events += "trimUser:$userId"
            onTrimUser(userId)
        }

        override fun sweepExpiredDecryptedCopies() {
            events += "sweepExpiredDecryptedCopies"
        }

        private fun List<ProtonGalleryPhoto>.hydrated(availability: ProtonStoredRenditions) =
            map { photo -> availability.photo(photo.nodeUid, photo.captureTimeEpochSeconds) }
    }

    private class FakeQueueStore : ProtonThumbnailQueueStore {
        private val queues =
            Collections.synchronizedMap(mutableMapOf<Pair<String, ProtonQueueName>, List<ProtonThumbnailQueueEntry>>())
        val reads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun clear(userId: String) {
            ProtonQueueName.entries.forEach { queue -> queues.remove(userId to queue) }
        }

        override fun readQueue(
            userId: String,
            queue: ProtonQueueName,
        ): List<ProtonThumbnailQueueEntry> {
            reads += userId
            return queues[userId to queue].orEmpty()
        }

        override fun writeQueue(
            userId: String,
            queue: ProtonQueueName,
            entries: List<ProtonThumbnailQueueEntry>,
        ) {
            queues[userId to queue] = entries.toList()
        }
    }

    private class FakeClock(
        @Volatile var value: Long = 1_000L,
    ) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private companion object {
        val USER_A = UserId("a")
        val USER_B = UserId("b")
        const val TIMELINE = ProtonSyncKeys.QueueSource.TIMELINE
        const val ALBUM_COVERS = ProtonSyncKeys.QueueSource.ALBUM_COVERS
        const val TIMELINE_PREVIEWS = ProtonSyncKeys.QueueSource.TIMELINE_PREVIEWS
        val ALBUM_PHOTOS_AL1 = ProtonSyncKeys.QueueSource.albumPhotos("al1")
    }
}
