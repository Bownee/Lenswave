package com.bownee.lenswave.gallery

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bownee.lenswave.BuildConfig
import com.bownee.lenswave.R
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.LenswaveReleases
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import kotlinx.coroutines.launch

/**
 * Checks for a newer release at startup and shows the update dialog once the activity is resumed,
 * carrying an unshown version across configuration changes. The hosting activity must implement
 * [UpdateAvailableDialogFragment.Listener] and forward its callbacks here.
 */
internal class GalleryUpdatePresenter(
    private val activity: FragmentActivity,
    private val appUpdateChecker: AppUpdateChecker,
) : UpdateAvailableDialogFragment.Listener {
    private var pendingVersionName: String? = null

    fun restore(savedInstanceState: Bundle?) {
        pendingVersionName = savedInstanceState?.getString(STATE_PENDING_UPDATE_VERSION)
    }

    fun save(outState: Bundle) {
        pendingVersionName?.let { outState.putString(STATE_PENDING_UPDATE_VERSION, it) }
    }

    fun checkForUpdate() {
        activity.lifecycleScope.launch {
            val update = appUpdateChecker.findAvailableUpdate(BuildConfig.VERSION_NAME) ?: return@launch
            pendingVersionName = update.versionName
            showPendingUpdate()
        }
    }

    fun showPendingUpdate() {
        val versionName = pendingVersionName ?: return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val fragmentManager = activity.supportFragmentManager
        if (fragmentManager.isStateSaved) return
        if (fragmentManager.findFragmentByTag(UpdateAvailableDialogFragment.TAG) != null) {
            pendingVersionName = null
            return
        }
        UpdateAvailableDialogFragment.create(versionName, BuildConfig.VERSION_NAME).show(
            fragmentManager,
            UpdateAvailableDialogFragment.TAG,
        )
        pendingVersionName = null
    }

    override fun onUpdateRequested() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, LenswaveReleases.latestReleasePageUrl.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_browser_for_update, Toast.LENGTH_LONG).show()
        }
    }

    override fun onUpdateSnoozed(versionName: String) {
        activity.lifecycleScope.launch { appUpdateChecker.snooze(versionName) }
    }

    private companion object {
        const val STATE_PENDING_UPDATE_VERSION = "gallery.pending-update-version"
    }
}
