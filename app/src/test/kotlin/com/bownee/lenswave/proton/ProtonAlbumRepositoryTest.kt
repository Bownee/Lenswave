package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProgressUpdate
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.AlbumItem
import me.proton.drive.sdk.entity.NodeUid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.channels.WritableByteChannel
import java.time.Instant

/**
 * Drives the repository over a fake album cache; the SDK client lists no albums and answers an
 * album enumeration only once the test releases it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonAlbumRepositoryTest {
    private val cache = FakeAlbumCache()
    private val clients = FakeClientProvider()
    private val failures = mutableListOf<Pair<String, Throwable>>()
    private val snapshotSync =
        ProtonSnapshotSync(ProtonSnapshotCoordinator(NeverSynced, FakeClock())) { operation, error ->
            failures += operation to error
        }
    private val repository = ProtonAlbumRepository(clients, cache, snapshotSync)

    @Test
    fun `a removal keeps the published albums when the snapshot cannot be read right now`() =
        runTest {
            cache.albums[USER.id] = listOf(album("al1", photoCount = 2L))
            repository.loadCached(USER)
            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "Album"))
            cache.albumsReadable = false

            repository.removePhotos(USER, setOf("x"))

            assertEquals(
                listOf("al1"),
                repository.albumsState.value.albums
                    .map(ProtonAlbum::nodeUid),
            )
            assertTrue(repository.albumsState.value.hasLoaded)
        }

    @Test
    fun `a removal publishes the reconciled snapshot when it can be read`() =
        runTest {
            cache.albums[USER.id] = listOf(album("al1", photoCount = 2L))
            cache.albumPhotos[USER.id to "al1"] = listOf(photo("x", 2L), photo("y", 1L))
            repository.loadCached(USER)
            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "Album"))
            cache.onRemove = { cache.albums[USER.id] = listOf(album("al1", photoCount = 1L)) }

            repository.removePhotos(USER, setOf("x"))

            assertEquals(
                1L,
                repository.albumsState.value.albums
                    .single()
                    .photoCount,
            )
            assertEquals(
                listOf("y"),
                repository.albumPhotosState.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )
            assertEquals(listOf("y"), cache.albumPhotos.getValue(USER.id to "al1").map(ProtonGalleryPhoto::nodeUid))
        }

    @Test
    fun `a photo trashed while the album was enumerating is not written or published again`() =
        runTest {
            val album = ProtonAlbumReference("al1", "Album")
            cache.albums[USER.id] = listOf(album("al1", photoCount = 2L))
            cache.albumPhotos[USER.id to "al1"] = listOf(photo("x", 2L), photo("y", 1L))
            repository.loadCached(USER)
            repository.loadCachedAlbum(USER, album)
            clients.albumPhotos = listOf("x" to 2L, "y" to 1L, "z" to 3L)

            val sync = launch { repository.syncAlbumPhotoMetadata(USER, album, forceRemote = false) }
            runCurrent()
            assertTrue("the enumeration must be in flight", clients.enumerating.isCompleted)

            repository.removePhotos(USER, setOf("x"))

            assertEquals(
                listOf("y"),
                repository.albumPhotosState.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )
            clients.release.complete(Unit)
            sync.join()

            val published = repository.albumPhotosState.value
            assertEquals(listOf("z", "y"), published.photos.map(ProtonGalleryPhoto::nodeUid))
            assertTrue(published.hasLoaded)
            assertFalse(published.syncing)
            assertEquals(
                listOf("z", "y"),
                cache.albumPhotos.getValue(USER.id to "al1").map(ProtonGalleryPhoto::nodeUid),
            )
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `a photo trashed while the album was enumerating is dropped even after the album was closed`() =
        runTest {
            val album = ProtonAlbumReference("al1", "Album")
            cache.albums[USER.id] = listOf(album("al1", photoCount = 2L), album("al2", photoCount = 1L))
            cache.albumPhotos[USER.id to "al1"] = listOf(photo("x", 2L), photo("y", 1L))
            cache.albumPhotos[USER.id to "al2"] = listOf(photo("w", 5L))
            repository.loadCached(USER)
            repository.loadCachedAlbum(USER, album)
            clients.albumPhotos = listOf("x" to 2L, "y" to 1L, "z" to 3L)

            val sync = launch { repository.syncAlbumPhotoMetadata(USER, album, forceRemote = false) }
            runCurrent()
            assertTrue("the enumeration must be in flight", clients.enumerating.isCompleted)

            // The user leaves for another album, then trashes a photo of the first one.
            repository.loadCachedAlbum(USER, ProtonAlbumReference("al2", "Other"))
            repository.removePhotos(USER, setOf("x"))
            clients.release.complete(Unit)
            sync.join()

            assertEquals(
                listOf("z", "y"),
                cache.albumPhotos.getValue(USER.id to "al1").map(ProtonGalleryPhoto::nodeUid),
            )
            val published = repository.albumPhotosState.value
            assertEquals("al2", published.albumUid)
            assertEquals(listOf("w"), published.photos.map(ProtonGalleryPhoto::nodeUid))
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `an automatic albums refresh that drops most of the albums keeps the cache and fails`() =
        runTest {
            // Real album counts are small; the album floor refuses losing three of five.
            cache.albums[USER.id] = List(5) { index -> album("al$index", photoCount = 1L) }
            repository.loadCached(USER)

            repository.syncMetadata(USER, forceRemote = false)

            assertEquals(5, repository.albumsState.value.albums.size)
            assertTrue(repository.albumsState.value.refreshFailed)
            assertTrue(repository.albumsState.value.listingRefused)
            assertTrue(cache.events.isEmpty())
            assertTrue(failures.single().second is ProtonSuspiciousListingException)

            repository.syncMetadata(USER, forceRemote = true)

            assertTrue(
                repository.albumsState.value.albums
                    .isEmpty(),
            )
            assertFalse(repository.albumsState.value.listingRefused)
            assertEquals(listOf("writeAlbums:0", "reconcileAlbums:0"), cache.events)
        }

    @Test
    fun `an automatic album-photo refresh that drops most of the photos keeps the cache and fails`() =
        runTest {
            val album = ProtonAlbumReference("al1", "Album")
            cache.albumPhotos[USER.id to "al1"] = List(400) { index -> photo("p$index", index.toLong()) }
            repository.loadCachedAlbum(USER, album)
            clients.release.complete(Unit)

            repository.syncAlbumPhotoMetadata(USER, album, forceRemote = false)

            assertEquals(400, repository.albumPhotosState.value.photos.size)
            assertTrue(repository.albumPhotosState.value.refreshFailed)
            assertTrue(repository.albumPhotosState.value.listingRefused)
            assertTrue(cache.events.isEmpty())
            assertTrue(failures.single().second is ProtonSuspiciousListingException)

            repository.syncAlbumPhotoMetadata(USER, album, forceRemote = true)

            assertTrue(
                repository.albumPhotosState.value.photos
                    .isEmpty(),
            )
            assertFalse(repository.albumPhotosState.value.listingRefused)
            assertEquals(listOf("writeAlbumPhotos:al1:0"), cache.events)
        }

    @Test
    fun `opening an album switches the published album and keeps the list instance when unchanged`() =
        runTest {
            cache.albumPhotos[USER.id to "al1"] = listOf(photo("x", 2L))
            cache.albumPhotos[USER.id to "al2"] = listOf(photo("y", 1L))
            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "First"))
            val first = repository.albumPhotosState.value.photos

            // Reopening the same album with an unchanged cache keeps the published instance.
            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "First"))
            assertSame(first, repository.albumPhotosState.value.photos)

            repository.loadCachedAlbum(USER, ProtonAlbumReference("al2", "Second"))
            assertEquals("al2", repository.albumPhotosState.value.albumUid)
            assertEquals(
                listOf("y"),
                repository.albumPhotosState.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )

            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "First"))
            assertEquals("al1", repository.albumPhotosState.value.albumUid)
            assertEquals(
                listOf("x"),
                repository.albumPhotosState.value.photos
                    .map(ProtonGalleryPhoto::nodeUid),
            )

            assertTrue(repository.albumPhotosState.value.hasLoaded)
        }

    @Test
    fun `a slow read for an album opened earlier cannot overwrite the album opened after it`() =
        runTest {
            cache.albumPhotos[USER.id to "al1"] = listOf(photo("x", 2L))
            cache.albumPhotos[USER.id to "al2"] = listOf(photo("y", 1L))
            // While the first album's index is still being read, the user opens the second one.
            cache.onReadAlbumPhotos = { albumUid ->
                if (albumUid == "al1") repository.loadCachedAlbum(USER, ProtonAlbumReference("al2", "Second"))
            }

            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "First"))

            val published = repository.albumPhotosState.value
            assertEquals("al2", published.albumUid)
            assertEquals(listOf("y"), published.photos.map(ProtonGalleryPhoto::nodeUid))
            assertTrue(published.hasLoaded)
        }

    @Test
    fun `the capture time of a photo in the open album is known, one in a closed album is not`() =
        runTest {
            cache.albumPhotos[USER.id to "al1"] = listOf(photo("x", 2L))
            repository.loadCachedAlbum(USER, ProtonAlbumReference("al1", "Album"))

            assertEquals(2L, repository.publishedCaptureTime(USER, "x"))
            assertEquals(null, repository.publishedCaptureTime(USER, "y"))
            assertEquals(null, repository.publishedCaptureTime(UserId("other"), "x"))
        }

    @Test
    fun `a sync writes the album listing before it deletes the indexes of vanished albums`() =
        runTest {
            cache.albums[USER.id] = listOf(album("al1", photoCount = 2L))
            repository.loadCached(USER)

            repository.syncMetadata(USER, forceRemote = false)

            assertEquals(listOf("writeAlbums:0", "reconcileAlbums:0"), cache.events)
            assertTrue(
                repository.albumsState.value.albums
                    .isEmpty(),
            )
            assertTrue(failures.isEmpty())
        }

    private fun album(
        nodeUid: String,
        photoCount: Long,
    ) = ProtonAlbum(
        nodeUid = nodeUid,
        name = nodeUid,
        photoCount = photoCount,
        coverPhotoNodeUid = null,
        createdAtEpochSeconds = 0L,
        lastActivityEpochSeconds = 0L,
        hasCoverThumbnail = false,
        isShared = false,
    )

    private fun photo(
        nodeUid: String,
        captureTime: Long,
    ) = ProtonGalleryPhoto(nodeUid = nodeUid, captureTimeEpochSeconds = captureTime, hasThumbnail = false)

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

    /**
     * A proxy stands in for the wide SDK interface: it lists no albums, signals [enumerating] when
     * an album is enumerated and answers with [albumPhotos] once [release] completes.
     */
    private class FakeClientProvider : ProtonPhotosClientProvider {
        var albumPhotos: List<Pair<String, Long>> = emptyList()
        val enumerating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun get(userId: UserId): ProtonPhotosClient =
            Proxy.newProxyInstance(
                ProtonPhotosClient::class.java.classLoader,
                arrayOf(ProtonPhotosClient::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "enumerateAlbumNodeUids", "enumerateSharedWithMeNodeUids" -> {
                        emptyFlow<Any>()
                    }

                    "enumerateAlbum" -> {
                        flow {
                            enumerating.complete(Unit)
                            release.await()
                            albumPhotos.forEach { (nodeUid, captureTime) ->
                                emit(AlbumItem(NodeUid(nodeUid), Instant.ofEpochSecond(captureTime)))
                            }
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

        override suspend fun disconnect(userId: UserId) = error("no disconnect expected")

        override suspend fun downloadTo(
            userId: UserId,
            nodeUid: String,
            output: WritableByteChannel,
            onProgress: (ProgressUpdate) -> Unit,
        ) = error("no download expected")
    }

    private class FakeAlbumCache : ProtonAlbumCache {
        val albums = mutableMapOf<String, List<ProtonAlbum>>()
        val albumPhotos = mutableMapOf<Pair<String, String>, List<ProtonGalleryPhoto>>()
        val events = mutableListOf<String>()
        var albumsReadable = true
        var onRemove: () -> Unit = {}

        /** Runs while an album-photo index is being read, before the result is handed back. */
        var onReadAlbumPhotos: (albumUid: String) -> Unit = {}

        override fun storedRenditions(userId: String): ProtonStoredRenditions = ProtonStoredRenditions.NONE

        override fun readAlbumsSnapshot(
            userId: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonAlbum>? = if (albumsReadable) albums[userId] else null

        override fun writeAlbums(
            userId: String,
            albums: List<ProtonAlbum>,
        ) {
            events += "writeAlbums:${albums.size}"
            this.albums[userId] = albums
        }

        override fun readAlbumPhotosSnapshot(
            userId: String,
            albumUid: String,
            availability: ProtonStoredRenditions,
        ): List<ProtonGalleryPhoto>? {
            val photos = albumPhotos[userId to albumUid]?.map { it }
            onReadAlbumPhotos(albumUid)
            return photos
        }

        override fun writeAlbumPhotos(
            userId: String,
            albumUid: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            events += "writeAlbumPhotos:$albumUid:${photos.size}"
            albumPhotos[userId to albumUid] = photos
        }

        override fun removeAlbumPhotos(
            userId: String,
            nodeUids: Collection<String>,
        ) {
            events += "removeAlbumPhotos:${nodeUids.sorted().joinToString(",")}"
            albumPhotos.replaceAll { (owner, _), photos ->
                if (owner == userId) photos.filterNot { it.nodeUid in nodeUids } else photos
            }
            onRemove()
        }

        override fun reconcileAlbums(
            userId: String,
            remoteAlbumUids: Collection<String>,
        ) {
            events += "reconcileAlbums:${remoteAlbumUids.size}"
        }

        override fun thumbnailExists(
            userId: String,
            nodeUid: String,
        ): Boolean = false
    }

    private companion object {
        val USER = UserId("user")
    }
}
