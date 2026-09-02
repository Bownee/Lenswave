package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMetadataState
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
    fun `Proton trash does not claim to be empty before metadata loads`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.Trash(PhotoSource.PROTON),
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonMetadata = loadingMetadata(),
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
                protonAlbums = ProtonAlbumsState(hasLoaded = false),
                protonMetadata = loadingMetadata(),
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
                protonAlbumPhotos = ProtonAlbumPhotosState(
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = listOf(
                        ProtonGalleryPhoto("ready", 2, hasThumbnail = true),
                        ProtonGalleryPhoto("pending", 1, hasThumbnail = false),
                    ),
                    hasLoaded = true,
                    downloadingThumbnails = true,
                    downloadedThumbnailCount = 1,
                ),
            ),
        )

        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `paused album thumbnail hydration is silent`() {
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

        assertFalse(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
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
                protonMetadata = loadedMetadata(),
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
                protonMetadata = loadedMetadata(),
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
                protonMetadata = loadedMetadata(),
            ),
        )

        assertFalse(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
    }

    @Test
    fun `initial Proton metadata sync does not display refresh indicator`() {
        val state = factory.create(
            GalleryUiInputs(
                destination = GalleryDestination.ProtonTimeline,
                protonAccountStatus = ProtonAccountStatus.CONNECTED,
                protonGallery = ProtonGalleryState(syncing = true),
                protonMetadata = loadingMetadata(),
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
                protonGallery = ProtonGalleryState(hasLoaded = true),
                protonMetadata = loadedMetadata().copy(isLoading = true),
                isRefreshing = true,
            ),
        )

        assertTrue(state.isRefreshing)
        assertTrue(state.statusText.contains(R.string.loading_metadata.toString()))
    }

    @Test
    fun `thumbnail retry is silent`() {
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
                protonMetadata = loadedMetadata(),
            ),
        )

        assertFalse(state.isRefreshing)
        assertFalse(state.statusText.contains(R.string.downloading_thumbnails_progress.toString()))
        assertFalse(state.statusText.contains(R.string.proton_sync_timeout_retry_scheduled.toString()))
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
                protonMetadata = loadedMetadata(),
            ),
        )

        assertFalse(state.isRefreshing)
        assertNotNull(state.emptyState)
        assertFalse(state.statusText.contains(R.string.proton_sync_stopped.toString()))
    }

    private fun loadingMetadata() = ProtonMetadataState(
        userId = "user",
        isLoading = true,
        hasLoaded = false,
    )

    private fun loadedMetadata() = ProtonMetadataState(
        userId = "user",
        hasLoaded = true,
    )

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
