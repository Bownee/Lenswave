package com.bownee.lenswave.gallery

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.bownee.lenswave.R
import me.proton.core.domain.entity.UserId

/**
 * Confirms a gallery selection's move to Proton Trash and reports the outcome. The confirmation
 * is a [TrashConfirmationDialogFragment] whose answer reaches the activity's
 * [TrashConfirmationDialogFragment.Listener]; the mutation itself runs in [GalleryViewModel], so
 * a rotation while it is in flight neither cancels it nor loses its result.
 */
internal class GalleryDeletionCoordinator(
    private val host: Host,
    private val text: GalleryText,
) {
    /** The activity's side: the fragment manager's state, the confirmation dialog and the toasts. */
    internal interface Host {
        val stateSaved: Boolean
        val trashConfirmationShowing: Boolean

        fun showTrashConfirmation(
            userId: UserId,
            nodeUids: List<String>,
        )

        fun showMessage(
            message: String,
            long: Boolean,
        )
    }

    /** [userId] is the account the selection belongs to; the confirmation is bound to it. */
    fun delete(
        userId: UserId,
        assets: List<GalleryAsset>,
    ) {
        when (val decision = PhotoDeletionPolicy.decide(assets.map { it.toPhotoTarget() })) {
            PhotoDeletionDecision.Empty -> Unit
            is PhotoDeletionDecision.Allowed -> execute(userId, decision.plan)
        }
    }

    /** Shows what a mutation the view model ran came to. */
    fun showOutcome(event: GalleryMutationEvent.Trash) {
        when (event) {
            is GalleryMutationEvent.Trashed -> {
                host.showMessage(
                    text.quantity(
                        R.plurals.moved_to_proton_trash_count_result,
                        event.successfulCount,
                        event.successfulCount,
                    ),
                    long = false,
                )
                if (event.failedCount > 0) {
                    host.showMessage(
                        text.quantity(R.plurals.could_not_move_count, event.failedCount, event.failedCount),
                        long = true,
                    )
                }
            }

            GalleryMutationEvent.TrashFailed -> {
                host.showMessage(text.string(R.string.could_not_move_photos_to_proton_trash), long = true)
            }
        }
    }

    private fun execute(
        userId: UserId,
        plan: PhotoDeletionPlan,
    ) {
        when (plan.operation) {
            PhotoDeletionOperation.MOVE_TO_TRASH -> confirmMoveToTrash(userId, plan.targets)
        }
    }

    private fun confirmMoveToTrash(
        userId: UserId,
        photos: List<PhotoTarget>,
    ) {
        // The confirmation answers a tap that just happened; one that cannot show now is simply not shown.
        val decision =
            GalleryDialogPromptPolicy.decide(
                stateSaved = host.stateSaved,
                dialogShowing = host.trashConfirmationShowing,
            )
        if (decision != GalleryDialogPromptPolicy.Decision.SHOW) return
        host.showTrashConfirmation(userId, photos.map(PhotoTarget::nodeUid))
    }

    /** The activity as the coordinator's host. */
    internal class ActivityHost(
        private val activity: FragmentActivity,
    ) : Host {
        override val stateSaved: Boolean get() = activity.supportFragmentManager.isStateSaved

        override val trashConfirmationShowing: Boolean
            get() = activity.supportFragmentManager.findFragmentByTag(TrashConfirmationDialogFragment.TAG) != null

        override fun showTrashConfirmation(
            userId: UserId,
            nodeUids: List<String>,
        ) {
            TrashConfirmationDialogFragment
                .create(userId, nodeUids, singlePhoto = false)
                .show(activity.supportFragmentManager, TrashConfirmationDialogFragment.TAG)
        }

        override fun showMessage(
            message: String,
            long: Boolean,
        ) {
            Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}
