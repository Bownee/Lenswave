package com.bownee.lenswave.gallery

enum class PhotoDeletionOperation {
    MOVE_TO_TRASH,
    DELETE_PERMANENTLY,
}

data class PhotoTarget(
    val stableId: String,
    val nodeUid: String,
    val isTrashed: Boolean,
)

data class PhotoDeletionPlan(
    val operation: PhotoDeletionOperation,
    val targets: List<PhotoTarget>,
)

fun GalleryAsset.toPhotoTarget(): PhotoTarget = PhotoTarget(stableId, nodeUid, isTrashed)

sealed interface PhotoDeletionDecision {
    data class Allowed(val plan: PhotoDeletionPlan) : PhotoDeletionDecision
    data object Empty : PhotoDeletionDecision
}

object PhotoDeletionPolicy {
    fun decide(
        targets: List<PhotoTarget>,
        permanently: Boolean = targets.any(PhotoTarget::isTrashed),
    ): PhotoDeletionDecision {
        if (targets.isEmpty()) return PhotoDeletionDecision.Empty
        return PhotoDeletionDecision.Allowed(
            PhotoDeletionPlan(
                operation = if (permanently) {
                    PhotoDeletionOperation.DELETE_PERMANENTLY
                } else {
                    PhotoDeletionOperation.MOVE_TO_TRASH
                },
                targets = targets,
            ),
        )
    }
}
