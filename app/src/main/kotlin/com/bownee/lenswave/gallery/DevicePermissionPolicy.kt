package com.bownee.lenswave.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

@SuppressLint("InlinedApi") // Permission strings are selected behind matching API-level checks.
internal object DevicePermissionPolicy {
    fun permissions(apiLevel: Int): Array<String> = when {
        apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        apiLevel >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun accessLevel(
        apiLevel: Int,
        readMediaImagesGranted: Boolean,
        selectedPhotosGranted: Boolean,
        legacyReadGranted: Boolean,
    ): DeviceAccessLevel = when {
        apiLevel >= Build.VERSION_CODES.TIRAMISU && readMediaImagesGranted -> DeviceAccessLevel.FULL
        apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && selectedPhotosGranted -> DeviceAccessLevel.PARTIAL
        apiLevel < Build.VERSION_CODES.TIRAMISU && legacyReadGranted -> DeviceAccessLevel.FULL
        else -> DeviceAccessLevel.NONE
    }

    fun shouldOpenSettingsToManage(accessLevel: DeviceAccessLevel): Boolean =
        accessLevel != DeviceAccessLevel.NONE
}
