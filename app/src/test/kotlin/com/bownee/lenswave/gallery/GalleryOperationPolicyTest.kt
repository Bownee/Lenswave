package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryOperationPolicyTest {
    @Test
    fun everyAccessLevelChangeInvalidatesSnapshots() {
        assertTrue(
            GalleryOperationPolicy.shouldInvalidateDeviceSnapshots(
                DeviceAccessLevel.FULL,
                DeviceAccessLevel.PARTIAL,
            ),
        )
        assertTrue(
            GalleryOperationPolicy.shouldInvalidateDeviceSnapshots(
                DeviceAccessLevel.PARTIAL,
                DeviceAccessLevel.NONE,
            ),
        )
        assertFalse(
            GalleryOperationPolicy.shouldInvalidateDeviceSnapshots(
                DeviceAccessLevel.PARTIAL,
                DeviceAccessLevel.PARTIAL,
            ),
        )
    }

    @Test
    fun staleOrRevokedDeviceLoadsCannotPublish() {
        assertTrue(GalleryOperationPolicy.isCurrentDeviceLoad(2, 2, DeviceAccessLevel.PARTIAL))
        assertFalse(GalleryOperationPolicy.isCurrentDeviceLoad(1, 2, DeviceAccessLevel.FULL))
        assertFalse(GalleryOperationPolicy.isCurrentDeviceLoad(2, 2, DeviceAccessLevel.NONE))
    }
}
