package com.bownee.lenswave.gallery

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePermissionPolicyTest {
    @Test fun permissionContractsMatchSupportedApiFamilies() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            DevicePermissionPolicy.permissions(29),
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            ),
            DevicePermissionPolicy.permissions(33),
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            DevicePermissionPolicy.permissions(34),
        )
    }

    @Test fun fullPartialAndDeniedStatesStayDistinct() {
        assertEquals(DeviceAccessLevel.FULL, DevicePermissionPolicy.accessLevel(29, false, false, true))
        assertEquals(DeviceAccessLevel.FULL, DevicePermissionPolicy.accessLevel(33, true, false, false))
        assertEquals(DeviceAccessLevel.PARTIAL, DevicePermissionPolicy.accessLevel(34, false, true, false))
        assertEquals(DeviceAccessLevel.NONE, DevicePermissionPolicy.accessLevel(34, false, false, false))
        assertEquals(DeviceAccessLevel.FULL, DevicePermissionPolicy.accessLevel(34, true, true, false))
        assertEquals(
            DeviceAccessLevel.PARTIAL,
            DevicePermissionPolicy.accessLevel(
                apiLevel = 34,
                readMediaImagesGranted = true,
                selectedPhotosGranted = false,
                legacyReadGranted = false,
                readMediaVideosGranted = false,
            ),
        )
    }

    @Test fun partialAccessUsesReselectionAndFullAccessUsesSettings() {
        assertFalse(DevicePermissionPolicy.shouldOpenSettingsToManage(DeviceAccessLevel.NONE))
        assertFalse(DevicePermissionPolicy.shouldOpenSettingsToManage(DeviceAccessLevel.PARTIAL))
        assertTrue(DevicePermissionPolicy.shouldOpenSettingsToManage(DeviceAccessLevel.FULL))
    }
}
