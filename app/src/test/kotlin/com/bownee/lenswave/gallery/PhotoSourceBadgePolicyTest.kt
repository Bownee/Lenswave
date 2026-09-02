package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSourceBadgePolicyTest {
    @Test
    fun `combined view badges Proton sourced photos`() {
        val photo = photo(source = PhotoSource.PROTON)

        assertTrue(PhotoSourceBadgePolicy.shouldShow(GalleryDestination.Combined, photo))
    }

    @Test
    fun `combined view badges device photos with a Proton copy`() {
        val photo = photo(PhotoSource.DEVICE).withReplicas(
            listOf(PhotoReplica.Proton("proton-node", hasThumbnail = true)),
        )

        assertTrue(PhotoSourceBadgePolicy.shouldShow(GalleryDestination.Combined, photo))
    }

    @Test
    fun `combined view does not badge device-only photos`() {
        assertFalse(PhotoSourceBadgePolicy.shouldShow(GalleryDestination.Combined, photo(PhotoSource.DEVICE)))
    }

    @Test
    fun `source-specific views do not show the Proton badge`() {
        assertFalse(PhotoSourceBadgePolicy.shouldShow(GalleryDestination.ProtonTimeline, photo(PhotoSource.PROTON)))
    }

    private fun photo(source: PhotoSource) = when (source) {
        PhotoSource.DEVICE -> GalleryAsset.device(
            stableId = "photo",
            capturedAtEpochMillis = 0L,
            displayName = "photo",
            uri = "content://media/photo",
            collection = DeviceCollection.CAMERA,
            sizeBytes = 1,
            modifiedAtEpochMillis = 0L,
        )
        PhotoSource.PROTON -> GalleryAsset.proton(
            stableId = "photo",
            capturedAtEpochMillis = 0L,
            nodeUid = "proton-node",
            hasThumbnail = true,
        )
    }
}
