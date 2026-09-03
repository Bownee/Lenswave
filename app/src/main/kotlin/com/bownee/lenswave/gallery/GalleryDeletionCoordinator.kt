package com.bownee.lenswave.gallery

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bownee.lenswave.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId

internal class GalleryDeletionCoordinator(
    private val activity: FragmentActivity,
    private val deletionExecutor: PhotoDeletionExecutor,
    private val currentUserId: () -> UserId?,
    private val onSelectionCleared: () -> Unit,
) {
    private var mutationInFlight = false

    fun delete(assets: List<GalleryAsset>) {
        when (val decision = PhotoDeletionPolicy.decide(assets.map { it.toPhotoTarget() })) {
            PhotoDeletionDecision.Empty -> Unit
            is PhotoDeletionDecision.Allowed -> execute(decision.plan)
        }
    }

    private fun execute(plan: PhotoDeletionPlan) {
        when (plan.operation) {
            PhotoDeletionOperation.MOVE_TO_TRASH -> confirmMoveToTrash(plan.targets)
        }
    }

    private fun confirmMoveToTrash(photos: List<PhotoTarget>) {
        val count = photos.size
        AlertDialog
            .Builder(activity)
            .setTitle(q(R.plurals.move_to_proton_trash_count, count, count))
            .setMessage(q(R.plurals.recover_from_proton_trash_count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.move_to_trash) { _, _ -> moveToTrash(photos) }
            .show()
    }

    private fun moveToTrash(photos: List<PhotoTarget>) {
        if (mutationInFlight) return
        val userId = currentUserId() ?: return
        val nodeUids = photos.map(PhotoTarget::nodeUid)
        mutationInFlight = true
        activity.lifecycleScope.launch {
            try {
                val result = deletionExecutor.trashProton(userId, nodeUids)
                onSelectionCleared()
                showMessage(
                    q(R.plurals.moved_to_proton_trash_count_result, result.successfulCount, result.successfulCount),
                    Toast.LENGTH_SHORT,
                )
                if (result.failedCount > 0) {
                    showMessage(
                        q(R.plurals.could_not_move_count, result.failedCount, result.failedCount),
                        Toast.LENGTH_LONG,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                showMessage(activity.getString(R.string.could_not_move_photos_to_proton_trash), Toast.LENGTH_LONG)
            } finally {
                mutationInFlight = false
            }
        }
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
