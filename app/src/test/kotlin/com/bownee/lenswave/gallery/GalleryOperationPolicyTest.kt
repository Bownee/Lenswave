package com.bownee.lenswave.gallery

import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryOperationPolicyTest {
    @Test
    fun combinedMatchingRequiresExplicitDestinationAndEveryPrivacyPrecondition() {
        val allowed = Inputs()
        assertTrue(allowed.canStart())
        assertFalse(allowed.copy(destination = GalleryDestination.ProtonTimeline).canStart())
        assertFalse(allowed.copy(destination = GalleryDestination.Device()).canStart())
        assertFalse(allowed.copy(accessLevel = DeviceAccessLevel.NONE).canStart())
        assertFalse(allowed.copy(sessionTransitioning = true).canStart())
        assertFalse(allowed.copy(userId = null).canStart())
        assertFalse(allowed.copy(devicePhotosLoaded = false).canStart())
        assertFalse(allowed.copy(protonSyncing = true).canStart())
    }

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

    private data class Inputs(
        val destination: GalleryDestination = GalleryDestination.Combined,
        val accessLevel: DeviceAccessLevel = DeviceAccessLevel.FULL,
        val sessionTransitioning: Boolean = false,
        val userId: UserId? = UserId("user"),
        val devicePhotosLoaded: Boolean = true,
        val protonSyncing: Boolean = false,
    ) {
        fun canStart() = GalleryOperationPolicy.canStartCombinedMatching(
            destination,
            accessLevel,
            sessionTransitioning,
            userId,
            devicePhotosLoaded,
            protonSyncing,
        )
    }
}
