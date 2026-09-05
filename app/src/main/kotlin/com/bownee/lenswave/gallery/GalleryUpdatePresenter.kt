package com.bownee.lenswave.gallery

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.bownee.lenswave.BuildConfig
import com.bownee.lenswave.R
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.LenswaveReleases
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows the startup update check's result as soon as the fragment manager can take it (see
 * [GalleryUpdatePromptPolicy]), carrying an unshown version across configuration changes. The
 * hosting activity must implement [UpdateAvailableDialogFragment.Listener] and forward its
 * callbacks here. [scope] is the activity's lifecycle scope: the check itself runs in the
 * checker's own scope, so a recreated activity awaits the same result.
 */
internal class GalleryUpdatePresenter(
    private val host: Host,
    private val updates: Updates,
    private val scope: CoroutineScope,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
) : UpdateAvailableDialogFragment.Listener {
    /** The activity's side: the fragment manager's state and the dialog, the browser, the toast. */
    internal interface Host {
        val stateSaved: Boolean
        val updateDialogShowing: Boolean

        fun showUpdateDialog(
            versionName: String,
            currentVersionName: String,
        )

        /** Opens the latest release page; false when no app on the device can. */
        fun openReleasePage(): Boolean

        fun showNoBrowserNotice()
    }

    /** The process-wide startup check (see [AppUpdateChecker]). */
    internal interface Updates {
        /** The version an update is available for, or null when there is none or another activity took it. */
        suspend fun awaitStartupUpdate(currentVersionName: String): String?

        fun markStartupUpdateShown()

        fun snooze(versionName: String)
    }

    /** The version waiting to be shown; survives a recreation through [save] and [restore]. */
    var pendingVersionName: String? = null
        private set

    fun restore(savedInstanceState: Bundle?) {
        restorePendingVersion(savedInstanceState?.getString(STATE_PENDING_UPDATE_VERSION))
    }

    fun restorePendingVersion(versionName: String?) {
        pendingVersionName = versionName
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
        scope.launch {
            val versionName = updates.awaitStartupUpdate(currentVersionName) ?: return@launch
            // Stored first, then marked: nothing suspends in between, so a cancellation cannot leave
            // the process with the update marked as shown and no activity holding it.
            pendingVersionName = versionName
            updates.markStartupUpdateShown()
            showPendingUpdate()
        }
    }

    fun showPendingUpdate() {
        val decision =
            GalleryUpdatePromptPolicy.decide(
                pendingVersionName = pendingVersionName,
                stateSaved = host.stateSaved,
                dialogShowing = host.updateDialogShowing,
            )
        if (decision == GalleryUpdatePromptPolicy.Decision.NOTHING ||
            decision == GalleryUpdatePromptPolicy.Decision.WAIT
        ) {
            return
        }
        if (decision == GalleryUpdatePromptPolicy.Decision.SHOW) {
            host.showUpdateDialog(requireNotNull(pendingVersionName), currentVersionName)
        }
        pendingVersionName = null
    }

    override fun onUpdateRequested() {
        if (!host.openReleasePage()) host.showNoBrowserNotice()
    }

    override fun onUpdateSnoozed(versionName: String) {
        updates.snooze(versionName)
    }

    /** The activity as the presenter's host. */
    internal class ActivityHost(
        private val activity: FragmentActivity,
    ) : Host {
        override val stateSaved: Boolean get() = activity.supportFragmentManager.isStateSaved

        override val updateDialogShowing: Boolean
            get() = activity.supportFragmentManager.findFragmentByTag(UpdateAvailableDialogFragment.TAG) != null

        override fun showUpdateDialog(
            versionName: String,
            currentVersionName: String,
        ) {
            UpdateAvailableDialogFragment
                .create(versionName, currentVersionName)
                .show(activity.supportFragmentManager, UpdateAvailableDialogFragment.TAG)
        }

        override fun openReleasePage(): Boolean =
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, LenswaveReleases.latestReleasePageUrl.toUri()))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }

        override fun showNoBrowserNotice() {
            Toast.makeText(activity, R.string.no_browser_for_update, Toast.LENGTH_LONG).show()
        }
    }

    /** [AppUpdateChecker] as the presenter's update source. */
    internal class CheckerUpdates(
        private val checker: AppUpdateChecker,
    ) : Updates {
        override suspend fun awaitStartupUpdate(currentVersionName: String): String? =
            checker.awaitStartupUpdate(currentVersionName)?.versionName

        override fun markStartupUpdateShown() = checker.markStartupUpdateShown()

        override fun snooze(versionName: String) {
            checker.snoozeInBackground(versionName)
        }
    }

    private companion object {
        const val STATE_PENDING_UPDATE_VERSION = "gallery.pending-update-version"
    }
}
