package com.bownee.lenswave.gallery

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bownee.lenswave.BuildConfig
import com.bownee.lenswave.R
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.LenswaveReleases
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import kotlinx.coroutines.launch

/**
 * Shows the startup update check's result as soon as the fragment manager can take it (see
 * [GalleryUpdatePromptPolicy]), carrying an unshown version across configuration changes. The hosting activity must implement [UpdateAvailableDialogFragment.Listener]
 * and forward its callbacks here.
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

    /**
     * Called from every onCreate: the check itself runs once per process in [AppUpdateChecker]'s
     * scope, so an activity recreated while it is in flight awaits the same result, and one
     * recreated after the dialog was shown receives nothing.
     */
    fun checkForUpdate() {
        activity.lifecycleScope.launch {
            val update = appUpdateChecker.awaitStartupUpdate(BuildConfig.VERSION_NAME) ?: return@launch
            // Stored first, then marked: nothing suspends in between, so a cancellation cannot leave
            // the process with the update marked as shown and no activity holding it.
            pendingVersionName = update.versionName
            appUpdateChecker.markStartupUpdateShown()
            showPendingUpdate()
        }
    }

    fun showPendingUpdate() {
        val fragmentManager = activity.supportFragmentManager
        val decision =
            GalleryUpdatePromptPolicy.decide(
                pendingVersionName = pendingVersionName,
                stateSaved = fragmentManager.isStateSaved,
                dialogShowing = fragmentManager.findFragmentByTag(UpdateAvailableDialogFragment.TAG) != null,
            )
        if (decision == GalleryUpdatePromptPolicy.Decision.NOTHING ||
            decision == GalleryUpdatePromptPolicy.Decision.WAIT
        ) {
            return
        }
        if (decision == GalleryUpdatePromptPolicy.Decision.SHOW) {
            UpdateAvailableDialogFragment.create(requireNotNull(pendingVersionName), BuildConfig.VERSION_NAME).show(
                fragmentManager,
                UpdateAvailableDialogFragment.TAG,
            )
        }
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
