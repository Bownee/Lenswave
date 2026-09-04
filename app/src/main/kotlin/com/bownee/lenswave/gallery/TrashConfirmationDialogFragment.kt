package com.bownee.lenswave.gallery

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.bownee.lenswave.R

/**
 * Asks before photos go to Proton Trash. A fragment rather than a bare dialog: the fragment
 * manager restores it across a rotation and tears it down with the activity, and the answer
 * reaches whichever activity instance is current through [Listener].
 */
class TrashConfirmationDialogFragment : DialogFragment() {
    interface Listener {
        fun onTrashConfirmed(nodeUids: List<String>)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val nodeUids = requireArguments().getStringArrayList(ARG_NODE_UIDS).orEmpty()
        val singlePhoto = requireArguments().getBoolean(ARG_SINGLE_PHOTO)
        val count = nodeUids.size
        val title =
            if (singlePhoto) {
                getString(R.string.move_to_proton_trash_question)
            } else {
                resources.getQuantityString(R.plurals.move_to_proton_trash_count, count, count)
            }
        val message =
            if (singlePhoto) {
                getString(R.string.recover_from_proton_trash)
            } else {
                resources.getQuantityString(R.plurals.recover_from_proton_trash_count, count)
            }
        return AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.move_to_trash) { _, _ -> (activity as? Listener)?.onTrashConfirmed(nodeUids) }
            .create()
    }

    companion object {
        const val TAG = "trash-confirmation"
        private const val ARG_NODE_UIDS = "node-uids"
        private const val ARG_SINGLE_PHOTO = "single-photo"

        /** [singlePhoto] picks the viewer's wording for the one photo on screen over the gallery's counts. */
        fun create(
            nodeUids: List<String>,
            singlePhoto: Boolean,
        ): TrashConfirmationDialogFragment =
            TrashConfirmationDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putStringArrayList(ARG_NODE_UIDS, ArrayList(nodeUids))
                        putBoolean(ARG_SINGLE_PHOTO, singlePhoto)
                    }
            }
    }
}
