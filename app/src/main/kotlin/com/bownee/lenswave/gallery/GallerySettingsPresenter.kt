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
import me.proton.core.usersettings.domain.usecase.PerformUpdateTelemetry

/**
 * The settings popup: connect/disconnect Proton, the privacy and telemetry dialog, app version.
 * The popup is a window-backed view anchored to a button, so it is kept here and dismissed in
 * [dispose]; the dialogs are fragments ([PrivacySettingsDialogFragment],
 * [DisconnectProtonDialogFragment]) that the fragment manager restores across a rotation, and
 * the hosting activity forwards their answers to [saveTelemetryPreference] and [onDisconnectProton].
 */
internal class GallerySettingsPresenter(
    private val activity: FragmentActivity,
    private val observeUserSettings: ObserveUserSettings,
    private val updateTelemetry: PerformUpdateTelemetry,
    private val currentUserId: () -> UserId?,
    private val privacySettings: ViewerPrivacySettings,
    private val onConnectProton: () -> Unit,
    private val onDisconnectProton: () -> Unit,
) {
    private var popup: PopupMenu? = null

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
                            privacySettings.blockScreenshots = !item.isChecked
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
    }

    /** The answer from [PrivacySettingsDialogFragment]; the account may have gone while the dialog was up. */
    fun saveTelemetryPreference(enabled: Boolean) {
        val userId = currentUserId()
        activity.lifecycleScope.launch {
            val updated = userId != null && runCatching { updateTelemetry(userId, enabled) }.isSuccess
            Toast
                .makeText(
                    activity,
                    if (updated) R.string.privacy_setting_saved else R.string.privacy_setting_failed,
                    Toast.LENGTH_LONG,
                ).show()
        }
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
        val canShow =
            GalleryDialogPromptPolicy.canShow(
                stateSaved = fragmentManager.isStateSaved,
                dialogShowing = fragmentManager.findFragmentByTag(tag) != null,
            )
        if (canShow) fragment.show(fragmentManager, tag)
    }

    private companion object {
        const val SETTINGS_CONNECT_PROTON = 1
        const val SETTINGS_DISCONNECT_PROTON = 2
        const val SETTINGS_PRIVACY = 3
        const val SETTINGS_BLOCK_SCREENSHOTS = 4
    }
}
