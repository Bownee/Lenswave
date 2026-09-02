package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryThumbnailPriorityPolicyTest {
    @Test
    fun visibleRowsIncludeOnlyMissingProtonThumbnails() {
        val rows = listOf(
            GalleryRow.MonthHeader("month", "Month"),
            GalleryRow.Photos(
                listOf(
                    GalleryAsset.proton("pending", 1, nodeUid = "pending", hasThumbnail = false),
                    GalleryAsset.proton("ready", 1, nodeUid = "ready", hasThumbnail = true),
                    GalleryAsset.device("device", 1, "", "uri", DeviceCollection.CAMERA, 0, 0),
                )
            ),
        )

        val pending = GalleryThumbnailPriorityPolicy.pendingNodeUids(rows, 0, rows.size)

        assertEquals(setOf("pending"), pending)
    }

    @Test
    fun visibleAlbumRowsIncludeMissingCovers() {
        val rows = listOf(
            GalleryRow.Albums(
                listOf(
                    album("pending", hasThumbnail = false),
                    album("ready", hasThumbnail = true),
                )
            )
        )

        val pending = GalleryThumbnailPriorityPolicy.pendingNodeUids(rows, 0, 1)

        assertEquals(setOf("cover-pending"), pending)
    }

    private fun album(id: String, hasThumbnail: Boolean) = ProtonAlbum(
        nodeUid = id,
        name = id,
        photoCount = 1,
        coverPhotoNodeUid = "cover-$id",
        createdAtEpochSeconds = 0,
        lastActivityEpochSeconds = 0,
        hasCoverThumbnail = hasThumbnail,
        isShared = false,
    )
}
