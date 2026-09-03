package com.bownee.lenswave.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Asks once for POST_NOTIFICATIONS so thumbnail downloads can show their progress notification.
 *
 * Must be constructed before the activity is started (for example as a field initializer) because
 * it registers an activity result launcher.
 */
internal class GalleryNotificationPermissionPrompter(private val activity: ComponentActivity) {
    private var requestInFlight = false
    private val preferences by lazy {
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestInFlight = false
        preferences.edit {
            putBoolean(KEY_THUMBNAIL_NOTIFICATION_PERMISSION_REQUESTED, true)
        }
    }

    fun requestIfNeeded(protonConnected: Boolean) {
        // Runtime notification permission only exists from Android 13; the explicit check also
        // keeps lint's InlinedApi analysis satisfied about the constant below.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!ThumbnailNotificationPermissionPolicy.shouldRequest(
                apiLevel = Build.VERSION.SDK_INT,
                protonConnected = protonConnected,
                permissionGranted = ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
                requestedBefore = preferences.getBoolean(
                    KEY_THUMBNAIL_NOTIFICATION_PERMISSION_REQUESTED,
                    false,
                ) || requestInFlight,
            )
        ) return
        requestInFlight = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val PREFERENCES_NAME = "permissions"
        const val KEY_THUMBNAIL_NOTIFICATION_PERMISSION_REQUESTED =
            "thumbnail-notification-permission-requested-v2"
    }
}
