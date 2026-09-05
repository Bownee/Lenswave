package com.bownee.lenswave.update

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.bownee.lenswave.R

class UpdateAvailableDialogFragment : DialogFragment() {
    interface Listener {
        fun onUpdateRequested()

        fun onUpdateSnoozed(versionName: String)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val versionName = requireArguments().getString(ARG_VERSION_NAME).orEmpty()
        val currentVersionName = requireArguments().getString(ARG_CURRENT_VERSION_NAME).orEmpty()
        return AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_available_message, versionName, currentVersionName))
            .setNegativeButton(R.string.not_now) { _, _ -> listener()?.onUpdateSnoozed(versionName) }
            .setPositiveButton(R.string.view_update) { _, _ -> listener()?.onUpdateRequested() }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        listener()?.onUpdateSnoozed(requireArguments().getString(ARG_VERSION_NAME).orEmpty())
        super.onCancel(dialog)
    }

    private fun listener(): Listener? = activity as? Listener

    companion object {
        const val TAG = "update-available"
        private const val ARG_VERSION_NAME = "version-name"
        private const val ARG_CURRENT_VERSION_NAME = "current-version-name"

        fun create(
            versionName: String,
            currentVersionName: String,
        ): UpdateAvailableDialogFragment =
            UpdateAvailableDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_VERSION_NAME, versionName)
                        putString(ARG_CURRENT_VERSION_NAME, currentVersionName)
                    }
            }
    }
}
