package com.bownee.lenswave.gallery

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.bownee.lenswave.R

/**
 * Confirms a gallery selection's move to Proton Trash and reports the outcome. The confirmation
 * is a [TrashConfirmationDialogFragment] whose answer reaches the activity's
 * [TrashConfirmationDialogFragment.Listener]; the mutation itself runs in [GalleryViewModel], so
 * a rotation while it is in flight neither cancels it nor loses its result.
 */
internal class GalleryDeletionCoordinator(
    private val activity: FragmentActivity,
) {
    fun delete(assets: List<GalleryAsset>) {
        when (val decision = PhotoDeletionPolicy.decide(assets.map { it.toPhotoTarget() })) {
            PhotoDeletionDecision.Empty -> Unit
            is PhotoDeletionDecision.Allowed -> execute(decision.plan)
        }
    }

    /** Shows what a mutation the view model ran came to. */
    fun showOutcome(event: GalleryMutationEvent) {
        when (event) {
            is GalleryMutationEvent.Trashed -> {
                showMessage(
                    q(R.plurals.moved_to_proton_trash_count_result, event.successfulCount, event.successfulCount),
                    Toast.LENGTH_SHORT,
                )
                if (event.failedCount > 0) {
                    showMessage(
                        q(R.plurals.could_not_move_count, event.failedCount, event.failedCount),
                        Toast.LENGTH_LONG,
                    )
                }
            }

            GalleryMutationEvent.TrashFailed -> {
                showMessage(activity.getString(R.string.could_not_move_photos_to_proton_trash), Toast.LENGTH_LONG)
            }
        }
    }

    private fun execute(plan: PhotoDeletionPlan) {
        when (plan.operation) {
            PhotoDeletionOperation.MOVE_TO_TRASH -> confirmMoveToTrash(plan.targets)
        }
    }

    private fun confirmMoveToTrash(photos: List<PhotoTarget>) {
        val fragmentManager = activity.supportFragmentManager
        // The confirmation answers a tap that just happened; one that cannot show now is simply not shown.
        val decision =
            GalleryDialogPromptPolicy.decide(
                stateSaved = fragmentManager.isStateSaved,
                dialogShowing = fragmentManager.findFragmentByTag(TrashConfirmationDialogFragment.TAG) != null,
            )
        if (decision != GalleryDialogPromptPolicy.Decision.SHOW) return
        TrashConfirmationDialogFragment
            .create(photos.map(PhotoTarget::nodeUid), singlePhoto = false)
            .show(fragmentManager, TrashConfirmationDialogFragment.TAG)
    }

    private fun showMessage(
        message: String,
        duration: Int,
    ) {
        Toast.makeText(activity, message, duration).show()
    }

    private fun q(
        resource: Int,
        count: Int,
        vararg arguments: Any,
    ): String = activity.resources.getQuantityString(resource, count, *arguments)
}
