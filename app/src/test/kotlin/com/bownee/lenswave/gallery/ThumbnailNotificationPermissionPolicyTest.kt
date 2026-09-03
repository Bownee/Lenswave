package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailNotificationPermissionPolicyTest {
    @Test
    fun requestsOnceForConnectedAccountsOnAndroidThirteenOrLater() {
        assertTrue(
            ThumbnailNotificationPermissionPolicy.shouldRequest(
                apiLevel = 33,
                protonConnected = true,
                permissionGranted = false,
                requestedBefore = false,
            )
        )
        assertFalse(
            ThumbnailNotificationPermissionPolicy.shouldRequest(
                apiLevel = 33,
                protonConnected = true,
                permissionGranted = false,
                requestedBefore = true,
            )
        )
    }

    @Test
    fun skipsTheRequestWhenItIsUnsupportedUnneededOrAlreadyGranted() {
        assertFalse(
            ThumbnailNotificationPermissionPolicy.shouldRequest(32, true, false, false)
        )
        assertFalse(
            ThumbnailNotificationPermissionPolicy.shouldRequest(33, false, false, false)
        )
        assertFalse(
            ThumbnailNotificationPermissionPolicy.shouldRequest(33, true, true, false)
        )
    }
}
