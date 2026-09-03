package com.bownee.lenswave.gallery

enum class PhotoDeletionOperation {
    MOVE_TO_TRASH,
}

data class PhotoTarget(
    val stableId: String,
    val nodeUid: String,
)

data class PhotoDeletionPlan(
    val operation: PhotoDeletionOperation,
    val targets: List<PhotoTarget>,
)

fun GalleryAsset.toPhotoTarget(): PhotoTarget = PhotoTarget(stableId, nodeUid)

sealed interface PhotoDeletionDecision {
    data class Allowed(val plan: PhotoDeletionPlan) : PhotoDeletionDecision
    data object Empty : PhotoDeletionDecision
}

object PhotoDeletionPolicy {
    fun decide(targets: List<PhotoTarget>): PhotoDeletionDecision {
        if (targets.isEmpty()) return PhotoDeletionDecision.Empty
        return PhotoDeletionDecision.Allowed(
            PhotoDeletionPlan(
                operation = PhotoDeletionOperation.MOVE_TO_TRASH,
                targets = targets,
            ),
        )
    }
}
