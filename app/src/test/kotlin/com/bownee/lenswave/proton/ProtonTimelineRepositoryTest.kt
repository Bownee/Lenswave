package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProgressUpdate
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.PhotosTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.channels.WritableByteChannel
import java.time.Instant

/**
 * Drives the repository over a fake cache, a fake tag listing and a fake SDK client; every sync
 * enumerates because nothing was ever stamped as synced. The listings can be held open so a test
 * decides what happens between an enumeration and its commit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonTimelineRepositoryTest {
    private val cache = FakeCache()
    private val tagListings = FakeTagListings()
    private val clients = FakeClientProvider()
    private val failures = mutableListOf<Pair<String, Throwable>>()
    private val snapshotSync =
        ProtonSnapshotSync(ProtonSnapshotCoordinator(NeverSynced, FakeClock())) { operation, error ->
            failures += operation to error
        }
    private val repository = ProtonTimelineRepository(clients, cache, snapshotSync, tagListings)

    @Test
    fun `a photo trashed while the favourites listing was enumerating is not written or published again`() =
        runTest {
            cache.timelines[USER.id] = listOf(photo("v~a1", 100L), photo("v~x", 500L))
            cache.tags[USER.id to ProtonMediaTag.FAVORITES] = listOf(photo("v~x", 500L))
            repository.loadCached(USER)
            tagListings.listings[ProtonMediaTag.FAVORITES] = listOf(photo("v~a1", 100L), photo("v~x", 500L))

            val sync = launch { repository.syncTagMetadata(USER, ProtonMediaTag.FAVORITES, forceRemote = false) }
            runCurrent()
            assertTrue("the listing must be in flight", tagListings.listing.isCompleted)

            repository.removePhotos(USER, setOf("v~x"))
            assertEquals(
                listOf("v~a1"),
                repository.state.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )
            tagListings.release.complete(Unit)
            sync.join()

            val favorites =
                repository.state.value.tags
                    .getValue(ProtonMediaTag.FAVORITES)
            assertEquals(listOf("v~a1"), favorites.photos.map(ProtonGalleryPhoto::nodeUid))
            assertTrue(favorites.hasLoaded)
            assertFalse(favorites.syncing)
            assertEquals(
                listOf("v~a1"),
                cache.tags.getValue(USER.id to ProtonMediaTag.FAVORITES).map(ProtonGalleryPhoto::nodeUid),
            )
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `a trash during a timeline enumeration completes at once and is not undone by the commit`() =
        runTest {
            cache.timelines[USER.id] = listOf(photo("v~a1", 100L), photo("v~x", 500L))
            cache.tags[USER.id to ProtonMediaTag.VIDEOS] = listOf(photo("v~x", 500L))
            repository.loadCached(USER)
            clients.holdEnumeration = true
            clients.timeline = listOf("v~a1" to 100L, "v~x" to 500L, "v~new" to 600L)

            val sync = launch { repository.syncMetadata(USER, forceRemote = false) }
            runCurrent()
            assertTrue("the enumeration must be in flight", clients.enumerating.isCompleted)

            val removal = launch { repository.removePhotos(USER, setOf("v~x")) }
            runCurrent()

            assertTrue("a trash must not wait for the enumeration", removal.isCompleted)
            assertEquals(
                listOf("v~a1"),
                repository.state.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )
            clients.release.complete(Unit)
            sync.join()

            val state = repository.state.value
            assertEquals(listOf("v~a1", "v~new"), state.photos.map(ProtonGalleryPhoto::nodeUid))
            assertFalse(state.syncing)
            assertTrue(
                state.tags
                    .getValue(ProtonMediaTag.VIDEOS)
                    .photos
                    .isEmpty(),
            )
            assertEquals(listOf("v~a1", "v~new"), cache.timelines.getValue(USER.id).map(ProtonGalleryPhoto::nodeUid))
            assertEquals(listOf("removePhotos:v~x", "writeIndex:2", "reconcilePhotos:2->2"), cache.events)
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `a favourite set while the favourites listing is enumerating survives the sync commit`() =
        runTest {
            cache.timelines[USER.id] = listOf(photo("v~a1", 100L), photo("v~x", 500L))
            cache.tags[USER.id to ProtonMediaTag.FAVORITES] = emptyList()
            repository.loadCached(USER)
            tagListings.listings[ProtonMediaTag.FAVORITES] = listOf(photo("v~a1", 100L))

            val sync = launch { repository.syncTagMetadata(USER, ProtonMediaTag.FAVORITES, forceRemote = false) }
            runCurrent()
            val toggle = launch { repository.setFavorite(USER, setOf("v~x"), favorite = true) }
            runCurrent()

            assertFalse("the toggle must wait for the sync in flight", toggle.isCompleted)
            tagListings.release.complete(Unit)
            sync.join()
            toggle.join()

            val favorites =
                repository.state.value.tags
                    .getValue(ProtonMediaTag.FAVORITES)
            assertEquals(listOf("v~x", "v~a1"), favorites.photos.map(ProtonGalleryPhoto::nodeUid))
            assertTrue(favorites.hasLoaded)
            assertEquals(
                listOf("v~x", "v~a1"),
                cache.tags.getValue(USER.id to ProtonMediaTag.FAVORITES).map(ProtonGalleryPhoto::nodeUid),
            )
        }

    @Test
    fun `a favourite toggle for another account leaves the published state and the cache alone`() =
        runTest {
            cache.timelines[USER.id] = listOf(photo("v~a1", 100L))
            cache.tags[USER.id to ProtonMediaTag.FAVORITES] = emptyList()
            repository.loadCached(USER)

            repository.setFavorite(UserId("other"), setOf("v~a1"), favorite = true)

            assertTrue(
                repository.state.value.tags
                    .getValue(ProtonMediaTag.FAVORITES)
                    .photos
                    .isEmpty(),
            )
            assertTrue(cache.events.none { it.startsWith("writeTag") })
        }

    @Test
    fun `a removal for another account updates that account's cache but not the published state`() =
        runTest {
            cache.timelines[USER.id] = listOf(photo("v~a1", 100L))
            cache.timelines["other"] = listOf(photo("o~a1", 100L))
            repository.loadCached(USER)

            repository.removePhotos(UserId("other"), setOf("o~a1"))

            assertEquals("user", repository.state.value.userId)
            assertEquals(
                listOf("v~a1"),
                repository.state.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )
            assertTrue(cache.timelines.getValue("other").isEmpty())
        }

    @Test
    fun `an automatic refresh that drops most of the library keeps the cached listing and fails the refresh`() =
        runTest {
            val cached = List(400) { index -> photo("v~p$index", index.toLong()) }
            cache.timelines[USER.id] = cached
            repository.loadCached(USER)
            clients.timeline = List(100) { index -> "v~p$index" to index.toLong() }

            repository.syncMetadata(USER, forceRemote = false)

            val state = repository.state.value
            assertEquals(400, state.photos.size)
            assertTrue(state.refreshFailed)
            assertFalse(state.syncing)
            assertEquals(400, cache.timelines.getValue(USER.id).size)
            assertTrue(cache.events.none { it.startsWith("reconcilePhotos") || it.startsWith("writeIndex") })
            assertTrue(failures.single().second is ProtonSuspiciousListingException)

            repository.syncMetadata(USER, forceRemote = true)

            assertEquals(100, repository.state.value.photos.size)
            assertFalse(repository.state.value.refreshFailed)
            assertEquals(100, cache.timelines.getValue(USER.id).size)
            // The new listing is on disk before anything it no longer names is deleted.
            assertEquals(listOf("writeIndex:100", "reconcilePhotos:400->100"), cache.events)
        }

    @Test
    fun `a timeline whose listing cannot be read is floored against its stored thumbnails`() =
        runTest {
            // No listing at all: a transient read failure and a fresh account look the same here.
            cache.thumbnails += List(400) { index -> "v~p$index" }
            clients.timeline = List(100) { index -> "v~p$index" to index.toLong() }

            repository.syncMetadata(USER, forceRemote = false)

            assertTrue(repository.state.value.refreshFailed)
            assertFalse(repository.state.value.hasLoaded)
            assertTrue(cache.events.none { it.startsWith("reconcilePhotos") || it.startsWith("writeIndex") })
            assertTrue(failures.single().second is ProtonSuspiciousListingException)

            repository.syncMetadata(USER, forceRemote = true)

            assertEquals(100, repository.state.value.photos.size)
            assertTrue(repository.state.value.hasLoaded)
            assertEquals(listOf("writeIndex:100", "reconcilePhotos:0->100"), cache.events)
        }

    @Test
    fun `an automatic tag refresh that drops most of the tag keeps the cache and fails`() =
        runTest {
            val cached = List(400) { index -> photo("v~p$index", index.toLong()) }
            cache.timelines[USER.id] = cached
            cache.tags[USER.id to ProtonMediaTag.VIDEOS] = cached
            repository.loadCached(USER)
            tagListings.listings[ProtonMediaTag.VIDEOS] = List(100) { index -> photo("v~p$index", index.toLong()) }
            tagListings.release.complete(Unit)

            repository.syncTagMetadata(USER, ProtonMediaTag.VIDEOS, forceRemote = false)

            val videos =
                repository.state.value.tags
                    .getValue(ProtonMediaTag.VIDEOS)
            assertEquals(400, videos.photos.size)
            assertTrue(videos.refreshFailed)
            assertEquals(400, cache.tags.getValue(USER.id to ProtonMediaTag.VIDEOS).size)
            assertTrue(failures.single().second is ProtonSuspiciousListingException)

            repository.syncTagMetadata(USER, ProtonMediaTag.VIDEOS, forceRemote = true)

            assertEquals(
                100,
                repository.state.value.tags
                    .getValue(ProtonMediaTag.VIDEOS)
                    .photos.size,
            )
            assertEquals(100, cache.tags.getValue(USER.id to ProtonMediaTag.VIDEOS).size)
        }

    @Test
    fun `a tag listing is committed whole while the timeline has not loaded`() =
        runTest {
            cache.tags[USER.id to ProtonMediaTag.VIDEOS] = listOf(photo("v~old", 1L))
            tagListings.listings[ProtonMediaTag.VIDEOS] = listOf(photo("v~new", 2L))
            tagListings.release.complete(Unit)

            repository.syncTagMetadata(USER, ProtonMediaTag.VIDEOS, forceRemote = false)

            assertEquals(
                listOf("v~new"),
                cache.tags.getValue(USER.id to ProtonMediaTag.VIDEOS).map(ProtonGalleryPhoto::nodeUid),
            )
        }

    private fun photo(
        nodeUid: String,
        captureTime: Long,
    ) = ProtonGalleryPhoto(nodeUid = nodeUid, captureTimeEpochSeconds = captureTime, hasThumbnail = false)

    /** Nothing was ever synced, so every listing with a snapshot is stale and enumerates. */
    private object NeverSynced : ProtonSyncMetadataStore {
        override fun readLastSuccessfulSync(
            userId: String,
            source: String,
        ): Long = 0L

        override fun writeLastSuccessfulSync(
            userId: String,
            source: String,
            timestampMillis: Long,
        ) {
        }
    }

    private class FakeClock : LenswaveClock {
        override fun nowMillis(): Long = 1_000_000_000L
    }

    /** Signals [listing] when asked and answers only once [release] completes. */
    private class FakeTagListings : ProtonTagListingClient {
        val listings = mutableMapOf<ProtonMediaTag, List<ProtonGalleryPhoto>>()
        val listing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun list(
            userId: UserId,
            volumeId: String,
            tag: ProtonMediaTag,
        ): List<ProtonGalleryPhoto> {
            listing.complete(Unit)
            release.await()
            return listings.getValue(tag)
        }
    }

    /**
     * The SDK client is a wide interface; a proxy answers the one call the repository makes
     * ([ProtonPhotosClient.enumerateTimeline]) and refuses everything else.
     */
    private class FakeClientProvider : ProtonPhotosClientProvider {
        var timeline: List<Pair<String, Long>> = emptyList()

        /** While true, an enumeration signals [enumerating] and answers only once [release] completes. */
        var holdEnumeration = false
        val enumerating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun get(userId: UserId): ProtonPhotosClient =
            Proxy.newProxyInstance(
                ProtonPhotosClient::class.java.classLoader,
                arrayOf(ProtonPhotosClient::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "enumerateTimeline" -> {
                        flow {
                            enumerating.complete(Unit)
                            if (holdEnumeration) release.await()
                            timeline.forEach { (nodeUid, captureTime) -> emit(timelineItem(nodeUid, captureTime)) }
                        }
                    }

                    "toString" -> {
                        "FakeProtonPhotosClient"
                    }

                    "hashCode" -> {
                        0
                    }

                    else -> {
                        error("The SDK must not be asked for ${method.name}")
                    }
                }
            } as ProtonPhotosClient

        private fun timelineItem(
            nodeUid: String,
            captureTime: Long,
        ) = PhotosTimelineItem(NodeUid(nodeUid), Instant.ofEpochSecond(captureTime))

        override suspend fun disconnect(userId: UserId) = error("no disconnect expected")

        override suspend fun downloadTo(
            userId: UserId,
            nodeUid: String,
            output: WritableByteChannel,
            onProgress: (ProgressUpdate) -> Unit,
        ) = error("no download expected")
    }

    private class FakeCache : ProtonTimelineCache {
        val timelines = mutableMapOf<String, List<ProtonGalleryPhoto>>()
        val tags = mutableMapOf<Pair<String, ProtonMediaTag>, List<ProtonGalleryPhoto>>()
        val thumbnails = mutableSetOf<String>()
        val events = mutableListOf<String>()

        override fun storedRenditions(userId: String): ProtonStoredRenditions =
            ProtonStoredRenditions(thumbnails.toSet(), emptySet()) { nodeUid -> nodeUid }

        override fun readTimelineSnapshot(
            userId: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? = timelines[userId]?.hydrated(availability)

        override fun writeIndex(
            userId: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            events += "writeIndex:${photos.size}"
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
            events += "writeTag:${tag.name}:${photos.size}"
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
            events += "reconcilePhotos:${cachedNodeUids.size}->${remoteNodeUids.size}"
        }

        override fun removePhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
            events += "removePhotos:${nodeUids.sorted().joinToString(",")}"
            timelines[userId] = timelines[userId].orEmpty().filterNot { it.nodeUid in nodeUids }
            tags.replaceAll { (owner, _), photos ->
                if (owner == userId) photos.filterNot { it.nodeUid in nodeUids } else photos
            }
        }

        private fun List<ProtonGalleryPhoto>.hydrated(availability: ProtonStoredRenditions) =
            map { photo -> availability.photo(photo.nodeUid, photo.captureTimeEpochSeconds) }
    }

    private companion object {
        val USER = UserId("user")
    }
}
