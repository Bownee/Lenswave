package com.bownee.lenswave.gallery

import me.proton.core.domain.entity.UserId

internal object GalleryOperationPolicy {
    fun shouldInvalidateDeviceSnapshots(
        previous: DeviceAccessLevel,
        current: DeviceAccessLevel,
    ): Boolean = previous != current

    fun isCurrentDeviceLoad(
        capturedGeneration: Long,
        currentGeneration: Long,
        accessLevel: DeviceAccessLevel,
    ): Boolean = capturedGeneration == currentGeneration && accessLevel != DeviceAccessLevel.NONE

    fun canStartCombinedMatching(
        destination: GalleryDestination,
        accessLevel: DeviceAccessLevel,
        sessionTransitioning: Boolean,
        userId: UserId?,
        devicePhotosLoaded: Boolean,
        protonSyncing: Boolean,
    ): Boolean = destination == GalleryDestination.Combined &&
        accessLevel != DeviceAccessLevel.NONE &&
        !sessionTransitioning &&
        userId != null &&
        devicePhotosLoaded &&
        !protonSyncing
}
