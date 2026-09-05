package com.bownee.lenswave.gallery

import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bownee.lenswave.BuildConfig
import com.bownee.lenswave.R
import com.bownee.lenswave.viewer.ViewerPrivacySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import me.proton.core.usersettings.domain.usecase.ObserveUserSettings

/**
 * The settings popup: connect/disconnect Proton, the privacy and telemetry dialog, app version.
 * The popup is a window-backed view anchored to a button, so it is kept here and dismissed in
 * [dispose]; the dialogs are fragments ([PrivacySettingsDialogFragment],
 * [DisconnectProtonDialogFragment]) that the fragment manager restores across a rotation, and
 * the hosting activity forwards their answers to [GalleryViewModel.saveTelemetryPreference] (whose
 * outcome comes back through [showTelemetryOutcome]) and [disconnectProtonConfirmed].
 */
internal class GallerySettingsPresenter(
    private val activity: FragmentActivity,
    private val observeUserSettings: ObserveUserSettings,
    private val currentUserId: () -> UserId?,
    private val privacySettings: ViewerPrivacySettings,
    private val onConnectProton: () -> Unit,
    private val onDisconnectProton: () -> Unit,
    /** The screenshot setting changed; the window flag must follow at once (see [showMenu]). */
    private val onScreenshotPolicyChanged: () -> Unit,
) {
    private var popup: PopupMenu? = null

    /** A dialog that arrived after onSaveInstanceState; [showPendingDialog] retries it on resume. */
    private var pendingDialog: Pair<DialogFragment, String>? = null

    fun showMenu(anchor: View) {
        popup?.dismiss()
        popup =
            PopupMenu(activity, anchor).apply {
                if (currentUserId() == null) {
                    menu.add(Menu.NONE, SETTINGS_CONNECT_PROTON, 0, R.string.connect_proton)
                } else {
                    menu.add(Menu.NONE, SETTINGS_DISCONNECT_PROTON, 0, R.string.disconnect_proton)
                }
                menu.add(Menu.NONE, SETTINGS_PRIVACY, 1, R.string.privacy_and_data)
                menu.add(Menu.NONE, SETTINGS_BLOCK_SCREENSHOTS, 2, R.string.block_screenshots).apply {
                    isCheckable = true
                    isChecked = privacySettings.blockScreenshots
                }
                menu
                    .add(
                        Menu.NONE,
                        Menu.NONE,
                        3,
                        activity.getString(R.string.app_version, BuildConfig.VERSION_NAME),
                    ).isEnabled = false
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        SETTINGS_CONNECT_PROTON -> {
                            onConnectProton()
                        }

                        SETTINGS_DISCONNECT_PROTON -> {
                            show(
                                DisconnectProtonDialogFragment(),
                                DisconnectProtonDialogFragment.TAG,
                            )
                        }

                        SETTINGS_PRIVACY -> {
                            showPrivacySettings()
                        }

                        SETTINGS_BLOCK_SCREENSHOTS -> {
                            // Applied now, not on the next resume: a popup toggle never pauses the
                            // activity, and pressing Home right after enabling it would otherwise
                            // store an unsecured recents snapshot.
                            privacySettings.blockScreenshots = !item.isChecked
                            onScreenshotPolicyChanged()
                        }
                    }
                    true
                }
                setOnDismissListener { if (popup === this) popup = null }
                show()
            }
    }

    /** Dismisses the popup; call from the activity's onDestroy. The dialog fragments follow the activity on their own. */
    fun dispose() {
        popup?.dismiss()
        popup = null
        pendingDialog = null
    }

    /** Call from onResume: shows a dialog that was held back because the state was saved when it was ready. */
    fun showPendingDialog() {
        val (fragment, tag) = pendingDialog ?: return
        pendingDialog = null
        show(fragment, tag)
    }

    /** What the view model's telemetry write came to; the write itself outlives this activity. */
    fun showTelemetryOutcome(saved: Boolean) {
        Toast
            .makeText(
                activity,
                if (saved) R.string.privacy_setting_saved else R.string.privacy_setting_failed,
                Toast.LENGTH_LONG,
            ).show()
    }

    /** The answer from [DisconnectProtonDialogFragment]. */
    fun disconnectProtonConfirmed() = onDisconnectProton()

    private fun showPrivacySettings() {
        val userId = currentUserId()
        if (userId == null) {
            show(
                PrivacySettingsDialogFragment.create(connected = false, telemetryEnabled = false),
                PrivacySettingsDialogFragment.TAG,
            )
            return
        }
        activity.lifecycleScope.launch {
            val enabled =
                runCatching { observeUserSettings(userId, false).first()?.telemetry == true }
                    .getOrDefault(false)
            // The read suspended; the state may have been saved meanwhile (see GalleryDialogPromptPolicy).
            show(
                PrivacySettingsDialogFragment.create(connected = true, telemetryEnabled = enabled),
                PrivacySettingsDialogFragment.TAG,
            )
        }
    }

    private fun show(
        fragment: DialogFragment,
        tag: String,
    ) {
        val fragmentManager = activity.supportFragmentManager
        val decision =
            GalleryDialogPromptPolicy.decide(
                stateSaved = fragmentManager.isStateSaved,
                dialogShowing = fragmentManager.findFragmentByTag(tag) != null,
            )
        when (decision) {
            GalleryDialogPromptPolicy.Decision.SHOW -> fragment.show(fragmentManager, tag)

            // The telemetry read suspended past onSaveInstanceState (a Home press while it ran):
            // the answer is kept and shown on resume rather than dropped without a word.
            GalleryDialogPromptPolicy.Decision.WAIT -> pendingDialog = fragment to tag

            GalleryDialogPromptPolicy.Decision.DROP -> Unit
        }
    }

    private companion object {
        const val SETTINGS_CONNECT_PROTON = 1
        const val SETTINGS_DISCONNECT_PROTON = 2
        const val SETTINGS_PRIVACY = 3
        const val SETTINGS_BLOCK_SCREENSHOTS = 4
    }
}
