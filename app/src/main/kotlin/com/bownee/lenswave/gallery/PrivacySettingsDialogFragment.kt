package com.bownee.lenswave.gallery

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.bownee.lenswave.R

/**
 * The privacy and data dialog: what Lenswave sends where and, with a Proton account connected,
 * the account's telemetry switch. A fragment so a rotation keeps it up, together with a switch
 * the user flipped but has not saved yet; the saved choice reaches the current activity through
 * [Listener].
 */
class PrivacySettingsDialogFragment : DialogFragment() {
    interface Listener {
        fun onTelemetryPreferenceSaved(enabled: Boolean)
    }

    private var desiredTelemetry = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val builder = AlertDialog.Builder(requireContext()).setTitle(R.string.privacy_and_data)
        if (!arguments.getBoolean(ARG_CONNECTED)) {
            return builder
                .setMessage(R.string.privacy_disconnected_message)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }
        desiredTelemetry =
            (savedInstanceState ?: arguments).getBoolean(
                STATE_DESIRED_TELEMETRY,
                arguments.getBoolean(ARG_TELEMETRY_ENABLED),
            )
        return builder
            .setMessage(R.string.privacy_connected_message)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.allow_proton_telemetry)),
                booleanArrayOf(desiredTelemetry),
            ) { _, _, checked -> desiredTelemetry = checked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.save,
            ) { _, _ -> (activity as? Listener)?.onTelemetryPreferenceSaved(desiredTelemetry) }
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DESIRED_TELEMETRY, desiredTelemetry)
    }

    companion object {
        const val TAG = "privacy-settings"
        private const val ARG_CONNECTED = "connected"
        private const val ARG_TELEMETRY_ENABLED = "telemetry-enabled"
        private const val STATE_DESIRED_TELEMETRY = "desired-telemetry"

        /** Without an account there is nothing to switch; [telemetryEnabled] is the account's current setting. */
        fun create(
            connected: Boolean,
            telemetryEnabled: Boolean,
        ): PrivacySettingsDialogFragment =
            PrivacySettingsDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putBoolean(ARG_CONNECTED, connected)
                        putBoolean(ARG_TELEMETRY_ENABLED, telemetryEnabled)
                    }
            }
    }
}
