package com.bownee.lenswave.gallery

internal object ThumbnailNotificationPermissionPolicy {
    fun shouldRequest(
        apiLevel: Int,
        protonConnected: Boolean,
        permissionGranted: Boolean,
        requestedBefore: Boolean,
    ): Boolean =
        apiLevel >= 33 &&
            protonConnected &&
            !permissionGranted &&
            !requestedBefore
}
