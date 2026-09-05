package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTagState
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryUiStateFactoryTest {
    private val factory =
        GalleryUiStateFactory(
            object : GalleryText {
                override fun string(
                    id: Int,
                    vararg arguments: Any,
                ) = "$id(${arguments.joinToString()})"

                override fun quantity(
                    id: Int,
                    quantity: Int,
                    vararg arguments: Any,
                ) = quantity.toString()
            },
        )

    @Test
    fun `uninitialized account session is connecting rather than disconnected`() {
        assertEquals(
            ProtonAccountStatus.CONNECTING,
            ProtonAccountStatus.resolve(
                initialized = false,
                transitioning = false,
                hasAccount = false,
                accountIsReady = false,
            ),
        )
        assertEquals(
            ProtonAccountStatus.DISCONNECTED,
            ProtonAccountStatus.resolve(
                initialized = true,
                transitioning = false,
                hasAccount = false,
                accountIsReady = false,
            ),
        )
    }

    @Test
    fun `account that is present but not ready is still connecting`() {
        assertEquals(
            ProtonAccountStatus.CONNECTING,
            ProtonAccountStatus.resolve(
                initialized = true,
                transitioning = false,
                hasAccount = true,
                accountIsReady = false,
            ),
        )
        assertEquals(
            ProtonAccountStatus.CONNECTED,
            ProtonAccountStatus.resolve(
                initialized = true,
                transitioning = false,
                hasAccount = true,
                accountIsReady = true,
            ),
        )
    }

    @Test
    fun `a session that ended on its own says so instead of inviting a first connection`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.DISCONNECTED,
                    signedOut = true,
                ),
            )

        assertEquals(GalleryEmptyAction.CONNECT_PROTON, state.emptyState?.action)
        assertEquals("${R.string.signed_out}()", state.emptyState?.title)
        assertEquals("${R.string.signed_out_message}()", state.emptyState?.message)
    }

    @Test
    fun `disconnected timeline offers a connect prompt`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.DISCONNECTED,
                ),
            )

        assertEquals(GalleryEmptyAction.CONNECT_PROTON, state.emptyState?.action)
        assertTrue(
            state.emptyState
                ?.title
                .orEmpty()
                .startsWith(R.string.connect_proton_photos.toString()),
        )
        assertFalse(state.isProtonConnected)
    }

    @Test
    fun `a failed refresh over cached photos raises the listing-refused flag instead of a panel`() {
        val photo = ProtonGalleryPhoto(nodeUid = "p1", captureTimeEpochSeconds = 1L, hasThumbnail = true)
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(photos = listOf(photo), hasLoaded = true, refreshFailed = true),
                ),
            )

        assertTrue(state.listingRefused)
        assertNull(state.emptyState)
        assertEquals(1, state.visibleAssets.size)
    }

    @Test
    fun `the listing-refused flag is off without a failure, without content, and for a skeleton`() {
        val photo = ProtonGalleryPhoto(nodeUid = "p1", captureTimeEpochSeconds = 1L, hasThumbnail = true)
        val loaded =
            GalleryUiInputs(
                destination = GalleryDestination.Timeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(photos = listOf(photo), hasLoaded = true),
            )
        assertFalse(factory.create(loaded).listingRefused)

        val failedAndEmpty =
            loaded.copy(protonGallery = ProtonGalleryState(hasLoaded = true, refreshFailed = true))
        assertFalse("the empty-state panel already reports this case", factory.create(failedAndEmpty).listingRefused)

        val failedWithPhotos =
            loaded.copy(
                protonGallery = ProtonGalleryState(photos = listOf(photo), hasLoaded = true, refreshFailed = true),
            )
        assertFalse(factory.skeleton(failedWithPhotos).listingRefused)
    }

    @Test
    fun `the listing-refused flag follows the section of the page on screen`() {
        val photo = ProtonGalleryPhoto(nodeUid = "p1", captureTimeEpochSeconds = 1L, hasThumbnail = true)
        val tagState =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Tag(ProtonMediaTag.SELFIES),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            hasLoaded = true,
                            tags =
                                mapOf(
                                    ProtonMediaTag.SELFIES to
                                        ProtonTagState(photos = listOf(photo), hasLoaded = true, refreshFailed = true),
                                ),
                        ),
                ),
            )
        assertTrue(tagState.listingRefused)

        val album =
            ProtonAlbum(
                nodeUid = "a1",
                name = "Album",
                photoCount = 1L,
                coverPhotoNodeUid = null,
                createdAtEpochSeconds = 0L,
                lastActivityEpochSeconds = 0L,
                hasCoverThumbnail = false,
                isShared = false,
            )
        val libraryState =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbums = ProtonAlbumsState(albums = listOf(album), hasLoaded = true, refreshFailed = true),
                ),
            )
        assertTrue(libraryState.listingRefused)

        val albumState =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.AlbumPhotos(ProtonAlbumReference("a1", "Album")),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbumPhotos =
                        ProtonAlbumPhotosState(
                            albumUid = "a1",
                            photos = listOf(photo),
                            hasLoaded = true,
                            refreshFailed = true,
                        ),
                ),
            )
        assertTrue(albumState.listingRefused)
    }

    @Test
    fun `timeline shows a failure panel when the refresh failed`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(hasLoaded = true, refreshFailed = true),
                ),
            )

        assertTrue(
            state.emptyState
                ?.title
                .orEmpty()
                .startsWith(R.string.could_not_load_proton_photos.toString()),
        )
    }

    @Test
    fun `tag filter shows nothing until it has loaded`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Tag(ProtonMediaTag.SELFIES),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            tags = mapOf(ProtonMediaTag.SELFIES to ProtonTagState(syncing = true)),
                        ),
                ),
            )

        assertNull(state.emptyState)
        assertTrue(state.visibleAssets.isEmpty())
    }

    @Test
    fun `tag filter shows an empty panel once loaded with no media`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Tag(ProtonMediaTag.SELFIES),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            tags = mapOf(ProtonMediaTag.SELFIES to ProtonTagState(hasLoaded = true)),
                        ),
                ),
            )

        val label = R.string.proton_tag_selfies.toString()
        assertEquals("${R.string.no_media_with_tag}($label())", state.emptyState?.title)
        assertNull(state.emptyState?.action)
    }

    @Test
    fun `tag filter shows a failure panel when it could not load`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Tag(ProtonMediaTag.SELFIES),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            tags =
                                mapOf(ProtonMediaTag.SELFIES to ProtonTagState(hasLoaded = true, refreshFailed = true)),
                        ),
                ),
            )

        assertTrue(
            state.emptyState
                ?.title
                .orEmpty()
                .startsWith(R.string.could_not_load_proton_tag.toString()),
        )
        assertTrue(
            state.emptyState
                ?.message
                .orEmpty()
                .startsWith(R.string.check_connection_refresh.toString()),
        )
    }

    @Test
    fun `album shows an empty panel naming the album once loaded with no photos`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.AlbumPhotos(album),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbumPhotos =
                        ProtonAlbumPhotosState(
                            albumUid = album.nodeUid,
                            albumName = album.name,
                            hasLoaded = true,
                        ),
                ),
            )

        assertEquals("${R.string.album_empty}()", state.emptyState?.title)
        assertEquals("${R.string.album_photos_appear_here}(Trip)", state.emptyState?.message)
    }

    @Test
    fun `album shows a failure panel when its photos could not load`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.AlbumPhotos(album),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbumPhotos =
                        ProtonAlbumPhotosState(
                            albumUid = album.nodeUid,
                            albumName = album.name,
                            hasLoaded = true,
                            refreshFailed = true,
                        ),
                ),
            )

        assertTrue(
            state.emptyState
                ?.title
                .orEmpty()
                .startsWith(R.string.could_not_load_album.toString()),
        )
    }

    @Test
    fun `album state for another album is ignored`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.AlbumPhotos(album),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbumPhotos =
                        ProtonAlbumPhotosState(
                            albumUid = "other",
                            albumName = "Other",
                            photos = listOf(ProtonGalleryPhoto("other-photo", 1, hasThumbnail = true)),
                            hasLoaded = true,
                        ),
                ),
            )

        assertTrue(state.visibleAssets.isEmpty())
        assertNull(state.emptyState)
        assertEquals("Trip", state.title)
    }

    @Test
    fun `restoring Proton session shows metadata loading without connect action`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTING,
                ),
            )

        assertNotNull(state.emptyState)
        assertNull(state.emptyState?.action)
        assertTrue(state.emptyState?.title?.contains(R.string.loading_metadata.toString()) == true)
    }

    @Test
    fun `restoring Proton session shows cached photos instead of the loading panel`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTING,
                    currentUserId = UserId("user"),
                    protonGallery =
                        ProtonGalleryState(
                            userId = "user",
                            hasLoaded = true,
                            photos =
                                listOf(
                                    ProtonGalleryPhoto(
                                        nodeUid = "photo",
                                        captureTimeEpochSeconds = 10L,
                                        hasThumbnail = false,
                                    ),
                                ),
                        ),
                ),
            )

        assertNull(state.emptyState)
        assertEquals(1, state.visibleAssets.size)
    }

    @Test
    fun `cached photos of another user stay hidden while the session switches`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTING,
                    currentUserId = UserId("next"),
                    protonGallery =
                        ProtonGalleryState(
                            userId = "previous",
                            hasLoaded = true,
                            photos =
                                listOf(
                                    ProtonGalleryPhoto(
                                        nodeUid = "photo",
                                        captureTimeEpochSeconds = 10L,
                                        hasThumbnail = false,
                                    ),
                                ),
                        ),
                ),
            )

        assertTrue(state.emptyState?.title?.contains(R.string.loading_metadata.toString()) == true)
        assertTrue(state.visibleAssets.isEmpty())
    }

    @Test
    fun `library reports album metadata loading without an empty state`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbums = ProtonAlbumsState(syncing = true),
                ),
            )

        assertNull(state.emptyState)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `library shows an empty panel once loaded with no albums`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbums = ProtonAlbumsState(hasLoaded = true),
                ),
            )

        assertEquals(emptyList<String>(), state.librarySectionKeys())
        assertTrue(
            state.emptyState
                ?.title
                .orEmpty()
                .startsWith(R.string.no_albums.toString()),
        )
    }

    @Test
    fun `library shows a failure panel when albums could not load`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbums = ProtonAlbumsState(hasLoaded = true, refreshFailed = true),
                ),
            )

        assertTrue(
            state.emptyState
                ?.title
                .orEmpty()
                .startsWith(R.string.could_not_load_albums.toString()),
        )
    }

    @Test
    fun `library offers only a connect prompt when disconnected`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.DISCONNECTED,
                ),
            )

        assertEquals(listOf("proton"), state.librarySectionKeys())
        val actions =
            (state.content as GalleryContent.Library)
                .sections
                .flatMap { it.items }
                .filterIsInstance<LibraryItem.Entry>()
                .map { it.action }
        assertEquals(listOf(LibraryAction.Request(GalleryEmptyAction.CONNECT_PROTON)), actions)
    }

    @Test
    fun `library does not show timeline metadata activity`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(syncing = true),
                    protonAlbums =
                        ProtonAlbumsState(
                            albums = listOf(ProtonAlbum("ready", "Ready", 1, "cover", 1, 2, true, false)),
                            hasLoaded = true,
                        ),
                ),
            )

        assertNull(state.emptyState)
    }

    @Test
    fun `every destination carries a title`() {
        val album = ProtonAlbumReference("album", "Trip")
        val titles =
            listOf(
                GalleryDestination.Timeline to R.string.photos.toString(),
                GalleryDestination.Tag(ProtonMediaTag.VIDEOS) to R.string.proton_tag_videos.toString(),
                GalleryDestination.Library to R.string.albums.toString(),
                GalleryDestination.AlbumPhotos(album) to "Trip",
            )

        titles.forEach { (destination, expected) ->
            assertTrue(factory.create(GalleryUiInputs(destination = destination)).title.startsWith(expected))
        }
    }

    @Test
    fun `timeline does not show albums metadata activity`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(hasLoaded = true),
                    protonAlbums = ProtonAlbumsState(syncing = true),
                ),
            )

        assertNotNull(state.emptyState)
    }

    @Test
    fun `cached timeline background refresh is visually silent`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(hasLoaded = true, syncing = true),
                ),
            )

        assertNotNull(state.emptyState)
    }

    @Test
    fun `cached albums background refresh is visually silent`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbums =
                        ProtonAlbumsState(
                            albums = listOf(ProtonAlbum("ready", "Ready", 1, "cover", 1, 2, true, false)),
                            hasLoaded = true,
                            syncing = true,
                        ),
                ),
            )

        assertNull(state.emptyState)
    }

    @Test
    fun `album metadata is visible before its cover thumbnail loads`() {
        val album =
            ProtonAlbum(
                nodeUid = "album",
                name = "Trip",
                photoCount = 4,
                coverPhotoNodeUid = "cover",
                createdAtEpochSeconds = 1,
                lastActivityEpochSeconds = 2,
                hasCoverThumbnail = false,
                isShared = false,
            )
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Library,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbums = ProtonAlbumsState(albums = listOf(album), hasLoaded = true),
                ),
            )

        val albums = (state.content as GalleryContent.Library).sections.single { it.key == "albums" }
        assertEquals(listOf(LibraryItem.Album(album)), albums.items)
        assertNull(state.emptyState)
    }

    @Test
    fun `library content keeps its instance across publishes that only change a flag`() {
        val albums = listOf(ProtonAlbum("album", "Trip", 4, "cover", 1, 2, true, false))
        val inputs =
            GalleryUiInputs(
                destination = GalleryDestination.Library,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbums = ProtonAlbumsState(albums = albums, hasLoaded = true),
            )

        val first = factory.create(inputs).content
        val refreshing = factory.create(inputs.copy(isRefreshing = true)).content
        val syncing = factory.create(inputs.copy(protonAlbums = inputs.protonAlbums.copy(syncing = true))).content

        assertSame(first, refreshing)
        assertSame(first, syncing)
        assertNotSame(first, factory.create(inputs.copy(protonAccountStatus = ProtonAccountStatus.CONNECTING)).content)
        assertNotSame(
            first,
            factory.create(inputs.copy(protonAlbums = ProtonAlbumsState(albums = albums.toList()))).content,
        )
    }

    @Test
    fun `initial album photo metadata load does not display refresh indicator`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.AlbumPhotos(album),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonAlbumPhotos =
                        ProtonAlbumPhotosState(
                            albumUid = album.nodeUid,
                            albumName = album.name,
                            syncing = true,
                        ),
                ),
            )

        assertNull(state.emptyState)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `Proton timeline maps remote photos into gallery assets`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            photos = listOf(ProtonGalleryPhoto("node", 42, hasThumbnail = true)),
                            hasLoaded = true,
                        ),
                ),
            )

        assertEquals("proton:node", state.visibleAssets.single().stableId)
    }

    @Test
    fun `photo pages are published newest first with undated photos last`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            photos =
                                listOf(
                                    ProtonGalleryPhoto("undated", 0, hasThumbnail = true),
                                    ProtonGalleryPhoto("older", 10, hasThumbnail = true),
                                    ProtonGalleryPhoto("newer", 20, hasThumbnail = true),
                                    ProtonGalleryPhoto("also-older", 10, hasThumbnail = true),
                                ),
                            hasLoaded = true,
                        ),
                ),
            )

        assertEquals(
            listOf("proton:newer", "proton:also-older", "proton:older", "proton:undated"),
            state.visibleAssets.map(GalleryAsset::stableId),
        )
    }

    @Test
    fun `initial Proton metadata sync does not display refresh indicator`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(syncing = true),
                ),
            )

        assertFalse(state.isRefreshing)
        assertNull(state.emptyState)
    }

    @Test
    fun `manual refresh displays the refresh state`() {
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Timeline,
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery = ProtonGalleryState(hasLoaded = true, syncing = true),
                    isRefreshing = true,
                ),
            )

        assertTrue(state.isRefreshing)
    }

    @Test
    fun `Proton tag filter returns matching media with video and favorite state`() {
        val video = ProtonGalleryPhoto("volume~video", 42, hasThumbnail = true)
        val state =
            factory.create(
                GalleryUiInputs(
                    destination = GalleryDestination.Tag(ProtonMediaTag.VIDEOS),
                    protonAccountStatus = ProtonAccountStatus.CONNECTED,
                    protonGallery =
                        ProtonGalleryState(
                            photos = listOf(video, ProtonGalleryPhoto("volume~image", 41, true)),
                            hasLoaded = true,
                            tags =
                                mapOf(
                                    ProtonMediaTag.VIDEOS to ProtonTagState(listOf(video), hasLoaded = true),
                                    ProtonMediaTag.FAVORITES to ProtonTagState(listOf(video), hasLoaded = true),
                                ),
                        ),
                ),
            )

        val asset = state.visibleAssets.single()
        assertEquals(MediaKind.VIDEO, asset.mediaKind)
        assertTrue(asset.isFavorite)
    }

    @Test
    fun `skeleton carries the destination facts but neither photos nor an empty panel`() {
        val userId = UserId("user")
        val photos = ProtonGalleryState(photos = listOf(ProtonGalleryPhoto("volume~a", 1, true)), hasLoaded = true)
        val album = GalleryDestination.AlbumPhotos(ProtonAlbumReference("album", "Trip"))
        val inputs =
            GalleryUiInputs(
                destination = album,
                protonAccountStatus = ProtonAccountStatus.DISCONNECTED,
                protonGallery = photos,
                currentUserId = userId,
                isRefreshing = true,
            )

        val skeleton = factory.skeleton(inputs)

        assertEquals(album, skeleton.destination)
        assertEquals("Trip", skeleton.title)
        assertEquals(userId, skeleton.currentUserId)
        assertTrue(skeleton.isProtonConnected)
        assertTrue(skeleton.isRefreshing)
        assertTrue(skeleton.visibleAssets.isEmpty())
        assertNull(skeleton.emptyState)
        assertEquals(
            GalleryContent.Library(emptyList()),
            factory.skeleton(inputs.copy(destination = GalleryDestination.Library)).content,
        )
    }

    private fun GalleryUiState.librarySectionKeys(): List<String> =
        (content as GalleryContent.Library).sections.map { it.key }
}
