package com.bownee.lenswave.gallery

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.bownee.lenswave.R
import me.proton.core.domain.entity.UserId

/**
 * Asks before photos go to Proton Trash. A fragment rather than a bare dialog: the fragment
 * manager restores it across a rotation and tears it down with the activity, and the answer
* reaches whichever activity instance is current through [Listener]. The arguments also carry
 * the account that was signed in when the dialog was shown: they outlive a process death, and
 * the answer must not run against whichever account the restored session settles on.
 */
class TrashConfirmationDialogFragment : DialogFragment() {
    interface Listener {
        fun onTrashConfirmed(
            userId: UserId,
            nodeUids: List<String>,
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val userId = UserId(requireArguments().getString(ARG_USER_ID).orEmpty())
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
            .setPositiveButton(
                R.string.move_to_trash,
            ) { _, _ -> (activity as? Listener)?.onTrashConfirmed(userId, nodeUids) }
            .create()
    }

    companion object {
        const val TAG = "trash-confirmation"
        private const val ARG_USER_ID = "user-id"
        private const val ARG_NODE_UIDS = "node-uids"
        private const val ARG_SINGLE_PHOTO = "single-photo"

        /**
         * [userId] is the account the photos belong to; [singlePhoto] picks the viewer's wording
         * for the one photo on screen over the gallery's counts.
         */
        fun create(
            userId: UserId,
            nodeUids: List<String>,
            singlePhoto: Boolean,
        ): TrashConfirmationDialogFragment =
            TrashConfirmationDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_USER_ID, userId.id)
                        putStringArrayList(ARG_NODE_UIDS, ArrayList(nodeUids))
                        putBoolean(ARG_SINGLE_PHOTO, singlePhoto)
                    }
            }
    }
}
