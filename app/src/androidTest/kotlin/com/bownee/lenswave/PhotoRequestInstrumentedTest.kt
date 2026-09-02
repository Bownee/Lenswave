package com.bownee.lenswave

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRequestInstrumentedTest {
    @Test fun deviceAndProtonRequestsRoundTripWithoutCrossSourceFields() {
        val device = PhotoRequest.Device(
            stableId = "device:1",
            uri = "content://media/1",
            protonBackingNodeUids = listOf("backup"),
            capturedAt = 12L,
            displayName = "device.jpg",
            isTrashed = false,
        )
        val deviceIntent = device.writeTo(Intent())
        assertEquals(device, PhotoRequest.from(deviceIntent))
        assertFalse(deviceIntent.hasExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID))
        assertFalse(deviceIntent.hasExtra(PhotoViewerActivity.EXTRA_USER_ID))

        val proton = PhotoRequest.Proton(
            stableId = "proton:1",
            protonNodeUid = "node",
            userId = "user",
            capturedAt = 34L,
            displayName = "cloud.jpg",
            isTrashed = true,
        )
        val protonIntent = proton.writeTo(deviceIntent)
        assertEquals(proton, PhotoRequest.from(protonIntent))
        assertFalse(protonIntent.hasExtra(PhotoViewerActivity.EXTRA_URI))
        assertFalse(protonIntent.hasExtra(PhotoViewerActivity.EXTRA_PROTON_BACKING_NODE_UIDS))
    }
}
