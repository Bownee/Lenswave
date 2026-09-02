package com.bownee.lenswave.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bownee.lenswave.R
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

internal class GalleryDeletionCoordinator(
    private val activity: FragmentActivity,
    private val deletionExecutor: PhotoDeletionExecutor,
    private val currentUserId: () -> UserId?,
    private val onSelectionCleared: () -> Unit,
    private val onDevicePhotosChanged: () -> Unit,
    savedState: Bundle? = null,
) {
    private var pendingDevicePhotos: List<PhotoTarget.Device> = restoreTargets(savedState, STATE_PENDING)
    private val pendingAndroidTenPhotos = ArrayDeque<PhotoTarget.Device>().apply {
        addAll(restoreTargets(savedState, STATE_QUEUE))
    }
    private var pendingAndroidTenPhoto: PhotoTarget.Device? =
        restoreTargets(savedState, STATE_CURRENT).firstOrNull()
    private val deletedDeviceIds = savedState?.getStringArrayList(STATE_DELETED)?.toMutableSet()
        ?: mutableSetOf()
    private var protonMutationInFlight = false

    private val trashConsentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) completeDeviceTrashing(pendingDevicePhotos)
        pendingDevicePhotos = emptyList()
    }

    private val deleteConsentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (result.resultCode == Activity.RESULT_OK) completeDeviceDeletion(pendingDevicePhotos)
            pendingDevicePhotos = emptyList()
            return@registerForActivityResult
        }
        val photo = pendingAndroidTenPhoto
        pendingAndroidTenPhoto = null
        if (result.resultCode == Activity.RESULT_OK && photo != null) {
            activity.lifecycleScope.launch {
                val deletedCount = runCatching {
                    deletionExecutor.deleteDevice(photo.uri.toUri())
                }.getOrDefault(0)
                if (deletedCount > 0) deletedDeviceIds += photo.stableId
                deleteNextAndroidTenPhoto()
            }
        } else {
            finishAndroidTenDeletion()
        }
    }

    fun delete(assets: List<GalleryAsset>, permanently: Boolean) {
        when (val decision = PhotoDeletionPolicy.decide(assets.map { it.toPhotoTarget() }, permanently)) {
            PhotoDeletionDecision.Empty -> Unit
            PhotoDeletionDecision.MixedSources -> showMessage(
                activity.getString(R.string.select_one_source_to_delete),
                Toast.LENGTH_LONG,
            )
            is PhotoDeletionDecision.Allowed -> execute(decision.plan)
        }
    }

    fun deleteAllFromTrash(assets: List<GalleryAsset>) {
        val targets = assets.filter(GalleryAsset::isTrashed).map { it.toPhotoTarget() }
        if (targets.isNotEmpty()) confirmPermanentDeletion(targets, deleteAll = true)
    }

    fun saveState(): Bundle = Bundle().apply {
        putParcelableArrayList(STATE_PENDING, ArrayList(pendingDevicePhotos.map(::targetToBundle)))
        putParcelableArrayList(STATE_QUEUE, ArrayList(pendingAndroidTenPhotos.map(::targetToBundle)))
        putParcelableArrayList(
            STATE_CURRENT,
            ArrayList(listOfNotNull(pendingAndroidTenPhoto).map(::targetToBundle)),
        )
        putStringArrayList(STATE_DELETED, ArrayList(deletedDeviceIds))
    }

    private fun execute(plan: PhotoDeletionPlan) {
        when (plan.operation) {
            PhotoDeletionOperation.DELETE_PERMANENTLY -> confirmPermanentDeletion(plan.targets)
            PhotoDeletionOperation.MOVE_TO_TRASH -> when (plan.source) {
                PhotoSource.DEVICE -> moveDevicePhotosToTrash(plan.targets.filterIsInstance<PhotoTarget.Device>())
                PhotoSource.PROTON -> confirmMoveToProtonTrash(plan.targets.filterIsInstance<PhotoTarget.Proton>())
            }
        }
    }

    private fun confirmPermanentDeletion(
        photos: List<PhotoTarget>,
        deleteAll: Boolean = false,
    ) {
        val count = photos.size
        val sourceLabel = if (photos.first().source == PhotoSource.DEVICE) {
            activity.getString(R.string.device_trash)
        } else {
            activity.getString(R.string.proton_trash)
        }
        AlertDialog.Builder(activity)
            .setTitle(q(if (deleteAll) R.plurals.delete_all_permanently else R.plurals.delete_count_permanently, count, count))
            .setMessage(q(R.plurals.remove_from_trash_message, count, sourceLabel))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ -> deletePermanently(photos) }
            .show()
    }

    private fun deletePermanently(photos: List<PhotoTarget>) {
        when (photos.firstOrNull()?.source) {
            PhotoSource.DEVICE -> deleteDevicePhotosPermanently(photos.filterIsInstance<PhotoTarget.Device>())
            PhotoSource.PROTON -> deleteProtonPhotosPermanently(photos.filterIsInstance<PhotoTarget.Proton>())
            null -> Unit
        }
    }

    private fun deleteProtonPhotosPermanently(photos: List<PhotoTarget.Proton>) {
        if (protonMutationInFlight) return
        val userId = currentUserId() ?: return
        val nodeUids = photos.map(PhotoTarget.Proton::nodeUid)
        protonMutationInFlight = true
        activity.lifecycleScope.launch {
            try {
                val result = deletionExecutor.deleteProtonPermanently(userId, nodeUids)
                onSelectionCleared()
                showMessage(
                    q(R.plurals.permanently_deleted_count, result.successfulCount, result.successfulCount),
                    Toast.LENGTH_SHORT,
                )
                if (result.failedCount > 0) {
                    showMessage(
                        q(R.plurals.could_not_delete_count, result.failedCount, result.failedCount),
                        Toast.LENGTH_LONG,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                showMessage(activity.getString(R.string.could_not_permanently_delete_photos), Toast.LENGTH_LONG)
            } finally {
                protonMutationInFlight = false
            }
        }
    }

    private fun moveDevicePhotosToTrash(photos: List<PhotoTarget.Device>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = photos.map { it.uri.toUri() }
            if (uris.isEmpty()) return
            pendingDevicePhotos = photos
            val request = MediaStore.createTrashRequest(activity.contentResolver, uris, true)
            trashConsentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(q(R.plurals.delete_count_question, photos.size, photos.size))
            .setMessage(q(R.plurals.delete_selected_device_message, photos.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> beginAndroidTenDeletion(photos) }
            .show()
    }

    private fun deleteDevicePhotosPermanently(photos: List<PhotoTarget.Device>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val uris = photos.map { it.uri.toUri() }
        if (uris.isEmpty()) return
        pendingDevicePhotos = photos
        val request = MediaStore.createDeleteRequest(activity.contentResolver, uris)
        deleteConsentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    private fun beginAndroidTenDeletion(photos: List<PhotoTarget.Device>) {
        pendingDevicePhotos = photos
        pendingAndroidTenPhotos.clear()
        pendingAndroidTenPhotos.addAll(photos)
        deletedDeviceIds.clear()
        deleteNextAndroidTenPhoto()
    }

    private fun deleteNextAndroidTenPhoto() {
        activity.lifecycleScope.launch {
            while (pendingAndroidTenPhotos.isNotEmpty()) {
                val photo = pendingAndroidTenPhotos.removeFirst()
                try {
                    val deletedCount = deletionExecutor.deleteDevice(photo.uri.toUri())
                    if (deletedCount > 0) deletedDeviceIds += photo.stableId
                } catch (error: RecoverableSecurityException) {
                    pendingAndroidTenPhoto = photo
                    deleteConsentLauncher.launch(
                        IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build(),
                    )
                    return@launch
                }
            }
            finishAndroidTenDeletion()
        }
    }

    private fun finishAndroidTenDeletion() {
        val deleted = pendingDevicePhotos.filter { it.stableId in deletedDeviceIds }
        pendingDevicePhotos = emptyList()
        pendingAndroidTenPhotos.clear()
        deletedDeviceIds.clear()
        completeDeviceDeletion(deleted)
    }

    private fun completeDeviceDeletion(deleted: List<PhotoTarget.Device>) {
        onSelectionCleared()
        onDevicePhotosChanged()
        if (deleted.isEmpty()) return
        val message = if (deleted.any(PhotoTarget.Device::isTrashed)) {
            q(R.plurals.permanently_deleted_count, deleted.size, deleted.size)
        } else {
            q(R.plurals.deleted_count, deleted.size, deleted.size)
        }
        showMessage(message, Toast.LENGTH_SHORT)
    }

    private fun completeDeviceTrashing(trashed: List<PhotoTarget.Device>) {
        onSelectionCleared()
        onDevicePhotosChanged()
        if (trashed.isNotEmpty()) {
            showMessage(
                q(R.plurals.moved_to_device_trash_count, trashed.size, trashed.size),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun confirmMoveToProtonTrash(photos: List<PhotoTarget.Proton>) {
        val count = photos.size
        AlertDialog.Builder(activity)
            .setTitle(q(R.plurals.move_to_proton_trash_count, count, count))
            .setMessage(q(R.plurals.recover_from_proton_trash_count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.move_to_trash) { _, _ -> moveToProtonTrash(photos) }
            .show()
    }

    private fun moveToProtonTrash(photos: List<PhotoTarget.Proton>) {
        if (protonMutationInFlight) return
        val userId = currentUserId() ?: return
        val nodeUids = photos.map(PhotoTarget.Proton::nodeUid)
        protonMutationInFlight = true
        activity.lifecycleScope.launch {
            try {
                val result = deletionExecutor.trashProton(userId, nodeUids)
                onSelectionCleared()
                showMessage(
                    q(R.plurals.moved_to_proton_trash_count_result, result.successfulCount, result.successfulCount),
                    Toast.LENGTH_SHORT,
                )
                if (result.failedCount > 0) {
                    showMessage(q(R.plurals.could_not_move_count, result.failedCount, result.failedCount), Toast.LENGTH_LONG)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                showMessage(activity.getString(R.string.could_not_move_photos_to_proton_trash), Toast.LENGTH_LONG)
            } finally {
                protonMutationInFlight = false
            }
        }
    }

    private fun showMessage(message: String, duration: Int) {
        Toast.makeText(activity, message, duration).show()
    }

    private fun q(resource: Int, count: Int, vararg arguments: Any): String =
        activity.resources.getQuantityString(resource, count, *arguments)

    private companion object {
        const val STATE_PENDING = "deletion.pending"
        const val STATE_QUEUE = "deletion.queue"
        const val STATE_CURRENT = "deletion.current"
        const val STATE_DELETED = "deletion.deleted"

        fun targetToBundle(target: PhotoTarget.Device): Bundle = Bundle().apply {
            putString("stableId", target.stableId)
            putString("uri", target.uri)
            putBoolean("trashed", target.isTrashed)
        }

        @Suppress("DEPRECATION")
        fun restoreTargets(state: Bundle?, key: String): List<PhotoTarget.Device> =
            state?.getParcelableArrayList<Bundle>(key).orEmpty().mapNotNull { value ->
                val stableId = value.getString("stableId") ?: return@mapNotNull null
                val uri = value.getString("uri") ?: return@mapNotNull null
                PhotoTarget.Device(
                    stableId = stableId,
                    uri = uri,
                    isTrashed = value.getBoolean("trashed"),
                )
            }
    }
}
