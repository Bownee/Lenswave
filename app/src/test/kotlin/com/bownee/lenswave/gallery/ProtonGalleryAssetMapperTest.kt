package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonGalleryAssetMapperTest {
    @Test
    fun stableIdIsPrefixedWithTheProtonSource() {
        val asset =
            ProtonGalleryPhoto("node-1", captureTimeEpochSeconds = 0L, hasThumbnail = false)
                .toGalleryAsset()

        assertEquals("proton:node-1", asset.stableId)
        assertEquals("node-1", asset.nodeUid)
    }

    @Test
    fun captureTimeIsConvertedFromSecondsToMillis() {
        val asset =
            ProtonGalleryPhoto("node-1", captureTimeEpochSeconds = 1_700_000_000L, hasThumbnail = true)
                .toGalleryAsset()

        assertEquals(1_700_000_000_000L, asset.capturedAtEpochMillis)
    }

    @Test
    fun carriesThumbnailAvailabilityAndPresentationOverrides() {
        val asset =
            ProtonGalleryPhoto("node-1", captureTimeEpochSeconds = 1L, hasThumbnail = true)
                .toGalleryAsset(
                    displayName = "clip.mp4",
                    mediaKind = MediaKind.VIDEO,
                    tags = setOf(ProtonMediaTag.VIDEOS),
                )

        assertEquals(
            GalleryAsset(
                stableId = "proton:node-1",
                capturedAtEpochMillis = 1_000L,
                displayName = "clip.mp4",
                nodeUid = "node-1",
                hasThumbnail = true,
                mediaKind = MediaKind.VIDEO,
                tags = setOf(ProtonMediaTag.VIDEOS),
            ),
            asset,
        )
    }
}
