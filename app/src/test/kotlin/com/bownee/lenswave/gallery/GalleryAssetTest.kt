package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAssetTest {
    @Test(expected = IllegalArgumentException::class)
    fun `primary replica must be part of replicas`() {
        GalleryAsset(
            stableId = "photo",
            capturedAtEpochMillis = 1,
            primaryReplica = protonReplica("primary"),
            replicas = listOf(protonReplica("other")),
        )
    }

    @Test
    fun `adding replicas preserves provenance and removes duplicate identities`() {
        val asset = GalleryAsset.device(
            stableId = "device-photo",
            capturedAtEpochMillis = 1,
            displayName = "photo.jpg",
            uri = "content://photos/1",
            collection = DeviceCollection.CAMERA,
            sizeBytes = 100,
            modifiedAtEpochMillis = 2,
        ).withReplicas(
            listOf(
                protonReplica("remote"),
                protonReplica("remote"),
            ),
        )

        assertTrue(asset.isStoredInProton)
        assertEquals(listOf("remote"), asset.protonBackingNodeUids)
        assertEquals(2, asset.replicas.size)
    }

    private fun protonReplica(nodeUid: String) = PhotoReplica.Proton(
        nodeUid = nodeUid,
        hasThumbnail = true,
    )
}
