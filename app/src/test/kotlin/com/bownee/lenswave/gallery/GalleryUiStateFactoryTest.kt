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
import com.bownee.lenswave.proton.ProtonThumbnailWorkIssue
import com.bownee.lenswave.proton.ProtonThumbnailWorkStatus
import com.bownee.lenswave.proton.ProtonTrashState
import com.bownee.lenswave.proton.ProtonTrashPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryUiStateFactoryTest {
    private val factory = GalleryUiStateFactory(object : GalleryText {
        override fun string(id: Int, vararg arguments: Any) =
            "$id(${arguments.joinToString()})"

        override fun quantity(id: Int, quantity: Int, vararg arguments: Any) = quantity.toString()
    })

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
    fun `restoring Proton session shows metadata loading without connect action`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTING,
            ),
        )

        assertNotNull(state.emptyState)
        assertNull(state.emptyState?.action)
        assertTrue(state.emptyState?.title?.contains(R.string.loading_metadata.toString()) == true)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
    }

    @Test
    fun `combined photos remain usable without device permission`() {
        val protonPhoto = ProtonGalleryPhoto("proton-photo", 42, hasThumbnail = true)
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Combined,
                hasDeviceAccess = false,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(protonPhoto),
                    hasLoaded = true,
                ),
            ),
        )

        assertEquals("proton:proton-photo", state.visibleAssets.single().stableId)
        assertNull(state.emptyState)
    }

    @Test
    fun `empty combined photos offers device access when Proton is connected`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Combined,
                hasDeviceAccess = false,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(hasLoaded = true),
            ),
        )

        assertEquals(GalleryEmptyAction.REQUEST_DEVICE_ACCESS, state.emptyState?.action)
    }

    @Test
    fun `Proton trash does not claim to be empty before metadata loads`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonTrash = ProtonTrashState(syncing = true),
            ),
        )

        assertNull(state.emptyState)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
        assertFalse(state.isRefreshing)
        assertFalse(state.showDeleteAll)
    }

    @Test
    fun `loaded empty Proton trash shows its empty state`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonTrash = ProtonTrashState(hasLoaded = true),
            ),
        )

        assertNotNull(state.emptyState)
    }

    @Test
    fun `Proton albums do not claim to be empty before metadata loads`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbums,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbums = ProtonAlbumsState(syncing = true),
            ),
        )

        assertNull(state.emptyState)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `loaded empty Proton albums show their empty state`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbums,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbums = ProtonAlbumsState(hasLoaded = true),
            ),
        )

        assertNotNull(state.emptyState)
    }

    @Test
    fun `albums do not show timeline metadata activity`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbums,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(syncing = true),
                protonAlbums = ProtonAlbumsState(hasLoaded = true),
            ),
        )

        assertFalse(state.statusText.contains(R.string.loading_metadata.toString()))
        assertNotNull(state.emptyState)
    }

    @Test
    fun `trash does not show albums metadata activity`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbums = ProtonAlbumsState(syncing = true),
                protonTrash = ProtonTrashState(hasLoaded = true),
            ),
        )

        assertFalse(state.statusText.contains(R.string.loading_metadata.toString()))
        assertNotNull(state.emptyState)
    }

    @Test
    fun `timeline does not show trash metadata activity`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(hasLoaded = true),
                protonTrash = ProtonTrashState(syncing = true),
            ),
        )

        assertFalse(state.statusText.contains(R.string.loading_metadata.toString()))
        assertNotNull(state.emptyState)
    }

    @Test
    fun `cached timeline background refresh is visually silent`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(hasLoaded = true, syncing = true),
            ),
        )

        assertFalse(state.statusText.contains(R.string.loading_metadata.toString()))
        assertNotNull(state.emptyState)
    }

    @Test
    fun `cached albums background refresh is visually silent`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbums,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbums = ProtonAlbumsState(hasLoaded = true, syncing = true),
            ),
        )

        assertFalse(state.statusText.contains(R.string.loading_metadata.toString()))
        assertNotNull(state.emptyState)
    }

    @Test
    fun `cached trash background refresh is visually silent`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonTrash = ProtonTrashState(hasLoaded = true, syncing = true),
            ),
        )

        assertFalse(state.statusText.contains(R.string.loading_metadata.toString()))
        assertNotNull(state.emptyState)
    }

    @Test
    fun `album metadata is visible before its cover thumbnail loads`() {
        val album = ProtonAlbum(
            nodeUid = "album",
            name = "Trip",
            photoCount = 4,
            coverPhotoNodeUid = "cover",
            createdAtEpochSeconds = 1,
            lastActivityEpochSeconds = 2,
            hasCoverThumbnail = false,
            isShared = false,
        )
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbums,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbums = ProtonAlbumsState(albums = listOf(album), hasLoaded = true),
            ),
        )

        assertEquals(listOf(album), (state.content as GalleryContent.Albums).albums)
        assertNull(state.emptyState)
    }

    @Test
    fun `initial album photo metadata load does not display refresh indicator`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbumPhotos(album),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbumPhotos = ProtonAlbumPhotosState(
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    syncing = true,
                ),
            ),
        )

        assertNull(state.emptyState)
        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
    }

    @Test
    fun `album thumbnail hydration reports progress independently`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbumPhotos(album),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.Running(1, 25),
                ),
                protonAlbumPhotos = ProtonAlbumPhotosState(
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = listOf(
                        ProtonGalleryPhoto("ready", 2, hasThumbnail = true),
                        ProtonGalleryPhoto("pending", 1, hasThumbnail = false),
                    ),
                    hasLoaded = true,
                    downloadedThumbnailCount = 1,
                ),
            ),
        )

        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `paused album thumbnail hydration still reports durable readiness`() {
        val album = ProtonAlbumReference("album", "Trip")
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbumPhotos(album),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonAlbumPhotos = ProtonAlbumPhotosState(
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = listOf(ProtonGalleryPhoto("pending", 1, hasThumbnail = false)),
                    hasLoaded = true,
                ),
            ),
        )

        assertTrue(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `trash metadata is visible before its thumbnail loads`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonTrash = ProtonTrashState(
                    photos = listOf(ProtonTrashPhoto("photo", 2, hasThumbnail = false)),
                    hasLoaded = true,
                ),
            ),
        )

        assertEquals("proton-trash:photo", state.visibleAssets.single().stableId)
        assertNull(state.emptyState)
    }

    @Test
    fun `device destination filters the shared device snapshot by collection`() {
        val camera = deviceAsset("camera", DeviceCollection.CAMERA)
        val screenshot = deviceAsset("screenshot", DeviceCollection.SCREENSHOTS)

        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Device(DeviceCollection.SCREENSHOTS),
                hasDeviceAccess = true,
                devicePhotos = GallerySourceSnapshot(
                    items = listOf(camera, screenshot),
                    hasLoaded = true,
                ),
            ),
        )

        assertEquals(listOf(screenshot), state.visibleAssets)
    }

    @Test
    fun `device trash exposes delete all only after content is available`() {
        val trashedPhoto = deviceAsset("trashed", DeviceCollection.CAMERA, isTrashed = true)

        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.DEVICE),
                hasDeviceAccess = true,
                deviceTrash = GallerySourceSnapshot(
                    items = listOf(trashedPhoto),
                    hasLoaded = true,
                ),
            ),
        )

        assertTrue(state.showDeleteAll)
        assertEquals(listOf(trashedPhoto), state.visibleAssets)
    }

    @Test
    fun `Proton timeline maps remote photos into gallery assets`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(ProtonGalleryPhoto("node", 42, hasThumbnail = true)),
                    hasLoaded = true,
                ),
            ),
        )

        assertEquals("proton:node", state.visibleAssets.single().stableId)
        assertTrue(state.visibleAssets.single().isStoredInProton)
    }

    @Test
    fun `background worker with no pending thumbnails uses the normal count`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    hasLoaded = true,
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.Running(1, 25),
                ),
            ),
        )

        assertFalse(state.isRefreshing)
        assertNotNull(state.emptyState)
        assertFalse(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `background Proton thumbnail sync reports progress without refresh indicator`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(
                        ProtonGalleryPhoto("ready", 42, hasThumbnail = true),
                        ProtonGalleryPhoto("pending", 41, hasThumbnail = false),
                    ),
                    hasLoaded = true,
                    downloadedThumbnailCount = 1,
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.Running(1, 25),
                ),
            ),
        )

        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `albums report album cover progress rather than timeline progress`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonAlbums,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(ProtonGalleryPhoto("timeline", 1, hasThumbnail = false)),
                    hasLoaded = true,
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.Running(1, 25),
                ),
                protonAlbums = ProtonAlbumsState(
                    albums = listOf(
                        ProtonAlbum("ready", "Ready", 1, "cover-1", 1, 2, true, false),
                        ProtonAlbum("pending", "Pending", 1, "cover-2", 1, 2, false, false),
                    ),
                    hasLoaded = true,
                ),
            ),
        )

        assertTrue(state.statusText.contains("${R.string.downloading_thumbnails_progress}(1, 2)"))
    }

    @Test
    fun `trash reports trash thumbnail progress rather than timeline progress`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(ProtonGalleryPhoto("timeline", 1, hasThumbnail = false)),
                    hasLoaded = true,
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.Running(1, 25),
                ),
                protonTrash = ProtonTrashState(
                    photos = listOf(
                        ProtonTrashPhoto("ready", 2, hasThumbnail = true),
                        ProtonTrashPhoto("pending", 1, hasThumbnail = false),
                    ),
                    hasLoaded = true,
                ),
            ),
        )

        assertTrue(state.statusText.contains("${R.string.downloading_thumbnails_progress}(1, 2)"))
    }

    @Test
    fun `initial Proton metadata sync does not display refresh indicator`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(syncing = true),
            ),
        )

        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
    }

    @Test
    fun `manual refresh displays the refresh state and metadata status`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(hasLoaded = true, syncing = true),
                isRefreshing = true,
            ),
        )

        assertTrue(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
    }

    @Test
    fun `thumbnail retry keeps durable readiness visible`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(ProtonGalleryPhoto("pending", 42, hasThumbnail = false)),
                    hasLoaded = true,
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.RetryScheduled(
                        attempt = 2,
                        maximumAttempts = 25,
                        issue = ProtonThumbnailWorkIssue.TIMEOUT,
                    ),
                ),
            ),
        )

        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `stopped thumbnail work is silent`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    hasLoaded = true,
                    thumbnailWorkStatus = ProtonThumbnailWorkStatus.Stopped(
                        attempt = 25,
                        maximumAttempts = 25,
                        issue = ProtonThumbnailWorkIssue.ERROR,
                    ),
                ),
            ),
        )

        assertFalse(state.isRefreshing)
        assertNotNull(state.emptyState)
    }

    @Test
    fun `Proton tag filter returns matching media with video and favorite state`() {
        val video = ProtonGalleryPhoto("volume~video", 42, hasThumbnail = true)
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTag(ProtonMediaTag.VIDEOS),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(
                    photos = listOf(video, ProtonGalleryPhoto("volume~image", 41, true)),
                    hasLoaded = true,
                    tags = mapOf(
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

    private fun deviceAsset(
        id: String,
        collection: DeviceCollection,
        isTrashed: Boolean = false,
    ) = GalleryAsset.device(
        stableId = id,
        capturedAtEpochMillis = 1,
        displayName = "$id.jpg",
        uri = "content://photos/$id",
        collection = collection,
        sizeBytes = 100,
        modifiedAtEpochMillis = 2,
        isTrashed = isTrashed,
    )
}
