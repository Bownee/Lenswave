package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.ProtonGalleryReader
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTagState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoNavigationSourceProviderTest {
    private val events = mutableListOf<String>()
    private val reader = FakeReader(events)
    private val session =
        MutableStateFlow(ProtonAccountSessionState(activeUserId = USER, initialized = true))
    private val provider = PhotoNavigationSourceProvider(reader, session)

    @Test
    fun `a timeline not yet loaded for the user is read from the cache and ordered newest first`() =
        runTest {
            reader.timelineToLoad = listOf(photo("old", 1L), photo("new", 3L), photo("mid", 2L))

            val assets = provider.load(GalleryDestination.Timeline, USER)

            assertEquals(listOf("syncTimeline:u"), events)
            assertEquals(listOf("new", "mid", "old"), assets?.map(GalleryAsset::nodeUid))
        }

    @Test
    fun `a loaded timeline is mapped without another read and a tag page uses the tag's photos`() =
        runTest {
            reader.state.value =
                ProtonGalleryState(
                    userId = USER.id,
                    photos = listOf(photo("p1", 2L), photo("p2", 1L)),
                    hasLoaded = true,
                    tags = mapOf(ProtonMediaTag.VIDEOS to ProtonTagState(listOf(photo("p2", 1L)), hasLoaded = true)),
                )

            assertEquals(
                listOf("p1", "p2"),
                provider.load(GalleryDestination.Timeline, USER)?.map(GalleryAsset::nodeUid),
            )
            assertEquals(
                listOf("p2"),
                provider.load(GalleryDestination.Tag(ProtonMediaTag.VIDEOS), USER)?.map(GalleryAsset::nodeUid),
            )
            assertEquals(emptyList<String>(), events)
        }

    @Test
    fun `an album is loaded from its cache and the library page has no photo list`() =
        runTest {
            val album = ProtonAlbumReference("al", "Album")
            reader.cachedAlbumPhotos = listOf(photo("a1", 1L))

            assertEquals(listOf("a1"), provider.load(GalleryDestination.AlbumPhotos(album), USER)?.map { it.nodeUid })
            assertEquals(listOf("loadCachedAlbum:al"), events)
            assertNull(provider.load(GalleryDestination.Library, USER))
        }

    @Test
    fun `a page that cannot be read or a session that never becomes active yields nothing`() =
        runTest {
            reader.failure = IllegalStateException("no snapshot")
            assertNull(provider.load(GalleryDestination.Timeline, USER))

            session.value = ProtonAccountSessionState(initialized = true, transitioning = true)
            reader.failure = null
            assertNull(provider.load(GalleryDestination.Timeline, USER))
        }

    private fun photo(
        nodeUid: String,
        captureTime: Long,
    ) = ProtonGalleryPhoto(nodeUid = nodeUid, captureTimeEpochSeconds = captureTime, hasThumbnail = true)

    private class FakeReader(
        private val events: MutableList<String>,
    ) : ProtonGalleryReader {
        override val state = MutableStateFlow(ProtonGalleryState())
        override val albumsState = MutableStateFlow(ProtonAlbumsState())
        override val albumPhotosState = MutableStateFlow(ProtonAlbumPhotosState())
        var timelineToLoad: List<ProtonGalleryPhoto> = emptyList()
        var cachedAlbumPhotos: List<ProtonGalleryPhoto> = emptyList()
        var failure: Throwable? = null

        override suspend fun syncTimelineMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) {
            events += "syncTimeline:${userId.id}"
            failure?.let { throw it }
            state.value = ProtonGalleryState(userId = userId.id, photos = timelineToLoad, hasLoaded = true)
        }

        override suspend fun syncTagMetadata(
            userId: UserId,
            tag: ProtonMediaTag,
            forceRemote: Boolean,
        ) {
            events += "syncTag:${tag.name}"
        }

        override suspend fun syncAlbumsMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) {
            events += "syncAlbums:${userId.id}"
        }

        override suspend fun loadCachedAlbum(
            userId: UserId,
            album: ProtonAlbumReference,
        ) {
            events += "loadCachedAlbum:${album.nodeUid}"
            albumPhotosState.value =
                ProtonAlbumPhotosState(
                    userId = userId.id,
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = cachedAlbumPhotos,
                    hasLoaded = true,
                )
        }

        override suspend fun syncAlbumPhotoMetadata(
            userId: UserId,
            album: ProtonAlbumReference,
            forceRemote: Boolean,
        ) {
            events += "syncAlbumPhotos:${album.nodeUid}"
        }
    }

    private companion object {
        val USER = UserId("u")
    }
}
