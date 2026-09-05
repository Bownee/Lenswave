package com.bownee.lenswave.gallery

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.bownee.lenswave.R

/**
 * Asks before the Proton account is removed. A fragment rather than a bare dialog: the fragment
 * manager restores it across a rotation and tears it down with the activity, and the answer
 * reaches whichever activity instance is current through [Listener].
 */
class DisconnectProtonDialogFragment : DialogFragment() {
    interface Listener {
        fun onDisconnectProtonConfirmed()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.disconnect_proton_question)
            .setMessage(R.string.disconnect_proton_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disconnect) { _, _ -> (activity as? Listener)?.onDisconnectProtonConfirmed() }
            .create()

    companion object {
        const val TAG = "disconnect-proton"
    }
}
