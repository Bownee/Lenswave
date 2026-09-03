package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `Proton-backed media exposes favorite targets`() {
        val asset = GalleryAsset.device(
            stableId = "device-photo",
            capturedAtEpochMillis = 1,
            displayName = "photo.jpg",
            uri = "content://photos/1",
            collection = DeviceCollection.CAMERA,
            sizeBytes = 100,
            modifiedAtEpochMillis = 2,
        ).withReplicas(listOf(protonReplica("first"), protonReplica("second")))

        assertTrue(asset.canFavoriteInProton)
        assertEquals(listOf("first", "second"), asset.protonReplicaNodeUids)
    }

    @Test
    fun `local-only and trashed media cannot be favorited in Proton`() {
        val local = GalleryAsset.device(
            stableId = "device-photo",
            capturedAtEpochMillis = 1,
            displayName = "photo.jpg",
            uri = "content://photos/1",
            collection = DeviceCollection.CAMERA,
            sizeBytes = 100,
            modifiedAtEpochMillis = 2,
        )
        val trashed = GalleryAsset.proton(
            stableId = "proton-photo",
            capturedAtEpochMillis = 1,
            nodeUid = "remote",
            hasThumbnail = true,
            isTrashed = true,
        )

        assertFalse(local.canFavoriteInProton)
        assertFalse(trashed.canFavoriteInProton)
    }

    @Test
    fun `favorite state is applied to every Proton replica`() {
        val asset = GalleryAsset.device(
            stableId = "device-photo",
            capturedAtEpochMillis = 1,
            displayName = "photo.jpg",
            uri = "content://photos/1",
            collection = DeviceCollection.CAMERA,
            sizeBytes = 100,
            modifiedAtEpochMillis = 2,
        ).withReplicas(listOf(protonReplica("first"), protonReplica("second")))

        val favorite = asset.withFavorite(true)
        val restored = favorite.withFavorite(false)

        assertTrue(favorite.isFavorite)
        assertTrue(favorite.protonReplicas.all { ProtonMediaTag.FAVORITES in it.tags })
        assertFalse(restored.isFavorite)
        assertTrue(restored.protonReplicas.all { ProtonMediaTag.FAVORITES !in it.tags })
    }

    private fun protonReplica(nodeUid: String) = PhotoReplica.Proton(
        nodeUid = nodeUid,
        hasThumbnail = true,
    )
}
