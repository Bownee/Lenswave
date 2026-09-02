package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePhotoClassifierTest {
    @Test
    fun `classifies camera photos from the standard camera path`() {
        assertEquals(
            DeviceCollection.CAMERA,
            DevicePhotoClassifier.classify(
                bucketName = "Camera",
                relativePath = "DCIM/Camera/",
                ownerPackageName = "com.sec.android.app.camera",
                isDownload = false,
            ),
        )
    }

    @Test
    fun `classifies screenshots before generic downloads`() {
        assertEquals(
            DeviceCollection.SCREENSHOTS,
            DevicePhotoClassifier.classify(
                bucketName = "Screenshots",
                relativePath = "Pictures/Screenshots/",
                ownerPackageName = null,
                isDownload = true,
            ),
        )
    }

    @Test
    fun `classifies whatsapp using its owner package`() {
        assertEquals(
            DeviceCollection.WHATSAPP,
            DevicePhotoClassifier.classify(
                bucketName = "Images",
                relativePath = "Pictures/",
                ownerPackageName = "com.whatsapp",
                isDownload = false,
            ),
        )
    }

    @Test
    fun `classifies media store downloads`() {
        assertEquals(
            DeviceCollection.DOWNLOADS,
            DevicePhotoClassifier.classify(
                bucketName = "Documents",
                relativePath = null,
                ownerPackageName = null,
                isDownload = true,
            ),
        )
    }

    @Test
    fun `keeps unrecognized locations in other`() {
        assertEquals(
            DeviceCollection.OTHER,
            DevicePhotoClassifier.classify(
                bucketName = "Edited",
                relativePath = "Pictures/Lenswave/",
                ownerPackageName = "com.bownee.lenswave",
                isDownload = false,
            ),
        )
    }
}
