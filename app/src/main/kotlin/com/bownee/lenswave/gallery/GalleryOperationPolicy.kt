package com.bownee.lenswave.gallery

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
}
