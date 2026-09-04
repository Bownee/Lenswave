package com.bownee.lenswave.gallery

import android.app.AlertDialog
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
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
 * The popup and dialogs are plain window-backed views, so they are kept here and dismissed in
 * [dispose] rather than left to leak the activity they were created on.
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
    private var dialog: AlertDialog? = null

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
                menu.add(Menu.NONE, SETTINGS_BLOCK_SCREENSHOTS, 2, R.string.block_screenshots_in_viewer).apply {
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
                        SETTINGS_CONNECT_PROTON -> onConnectProton()
                        SETTINGS_DISCONNECT_PROTON -> confirmDisconnectProton()
                        SETTINGS_PRIVACY -> showPrivacySettings()
                        SETTINGS_BLOCK_SCREENSHOTS -> privacySettings.blockScreenshots = !item.isChecked
                    }
                    true
                }
                setOnDismissListener { if (popup === this) popup = null }
                show()
            }
    }

    /** Dismisses whatever is open; call from the activity's onDestroy. */
    fun dispose() {
        popup?.dismiss()
        popup = null
        dialog?.dismiss()
        dialog = null
    }

    private fun show(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog =
            builder
                .setOnDismissListener { dialog = null }
                .show()
    }

    private fun showPrivacySettings() {
        val userId = currentUserId()
        if (userId == null) {
            show(
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.privacy_and_data)
                    .setMessage(R.string.privacy_disconnected_message)
                    .setPositiveButton(android.R.string.ok, null),
            )
            return
        }
        activity.lifecycleScope.launch {
            val enabled =
                runCatching { observeUserSettings(userId, false).first()?.telemetry == true }
                    .getOrDefault(false)
            var desired = enabled
            show(
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.privacy_and_data)
                    .setMessage(R.string.privacy_connected_message)
                    .setMultiChoiceItems(
                        arrayOf(activity.getString(R.string.allow_proton_telemetry)),
                        booleanArrayOf(enabled),
                    ) { _, _, checked -> desired = checked }
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        activity.lifecycleScope.launch {
                            val updated = runCatching { updateTelemetry(userId, desired) }.isSuccess
                            Toast
                                .makeText(
                                    activity,
                                    if (updated) R.string.privacy_setting_saved else R.string.privacy_setting_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    },
            )
        }
    }

    private fun confirmDisconnectProton() {
        show(
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.disconnect_proton_question)
                .setMessage(R.string.disconnect_proton_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.disconnect) { _, _ -> onDisconnectProton() },
        )
    }

    private companion object {
        const val SETTINGS_CONNECT_PROTON = 1
        const val SETTINGS_DISCONNECT_PROTON = 2
        const val SETTINGS_PRIVACY = 3
        const val SETTINGS_BLOCK_SCREENSHOTS = 4
    }
}
