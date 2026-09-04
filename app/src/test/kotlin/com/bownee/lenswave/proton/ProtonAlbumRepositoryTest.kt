package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProgressUpdate
import me.proton.drive.sdk.ProtonPhotosClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.channels.WritableByteChannel

/** Drives the repository over a fake album cache; the SDK client answers only the album listings, and with nothing. */
class ProtonAlbumRepositoryTest {
    private val cache = FakeAlbumCache()
    private val failures = mutableListOf<Pair<String, Throwable>>()
    private val snapshotSync =
        ProtonSnapshotSync(ProtonSnapshotCoordinator(NeverSynced, FakeClock())) { operation, error ->
            failures += operation to error
        }
    private val repository = ProtonAlbumRepository(FakeClientProvider(), cache, snapshotSync)

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

    /** A proxy stands in for the wide SDK interface; it lists no albums and refuses everything else. */
    private class FakeClientProvider : ProtonPhotosClientProvider {
        override suspend fun get(userId: UserId): ProtonPhotosClient =
            Proxy.newProxyInstance(
                ProtonPhotosClient::class.java.classLoader,
                arrayOf(ProtonPhotosClient::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "enumerateAlbumNodeUids", "enumerateSharedWithMeNodeUids" -> emptyFlow<Any>()
                    "toString" -> "FakeProtonPhotosClient"
                    "hashCode" -> 0
                    else -> error("The SDK must not be asked for ${method.name}")
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
        ): List<ProtonGalleryPhoto>? = albumPhotos[userId to albumUid]

        override fun writeAlbumPhotos(
            userId: String,
            albumUid: String,
            photos: List<ProtonGalleryPhoto>,
        ) {
            events += "writeAlbumPhotos:$albumUid:${photos.size}"
            albumPhotos[userId to albumUid] = photos
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
