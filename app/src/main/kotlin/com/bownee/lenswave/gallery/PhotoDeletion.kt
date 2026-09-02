package com.bownee.lenswave.gallery

enum class PhotoDeletionOperation {
    MOVE_TO_TRASH,
    DELETE_PERMANENTLY,
}

data class PhotoDeletionPlan(
    val source: PhotoSource,
    val operation: PhotoDeletionOperation,
    val targets: List<PhotoTarget>,
)

sealed interface PhotoTarget {
    val stableId: String
    val source: PhotoSource
    val isTrashed: Boolean

    data class Device(
        override val stableId: String,
        val uri: String,
        override val isTrashed: Boolean,
    ) : PhotoTarget {
        override val source = PhotoSource.DEVICE
    }

    data class Proton(
        override val stableId: String,
        val nodeUid: String,
        override val isTrashed: Boolean,
    ) : PhotoTarget {
        override val source = PhotoSource.PROTON
    }
}

fun GalleryAsset.toPhotoTarget(): PhotoTarget = when (val replica = primaryReplica) {
    is PhotoReplica.Device -> PhotoTarget.Device(stableId, replica.uri, replica.isTrashed)
    is PhotoReplica.Proton -> PhotoTarget.Proton(stableId, replica.nodeUid, replica.isTrashed)
}

sealed interface PhotoDeletionDecision {
    data class Allowed(val plan: PhotoDeletionPlan) : PhotoDeletionDecision
    data object Empty : PhotoDeletionDecision
    data object MixedSources : PhotoDeletionDecision
}

object PhotoDeletionPolicy {
    fun decide(
        targets: List<PhotoTarget>,
        permanently: Boolean = targets.any(PhotoTarget::isTrashed),
    ): PhotoDeletionDecision {
        if (targets.isEmpty()) return PhotoDeletionDecision.Empty
        val sources = targets.mapTo(mutableSetOf(), PhotoTarget::source)
        if (sources.size != 1) return PhotoDeletionDecision.MixedSources
        return PhotoDeletionDecision.Allowed(
            PhotoDeletionPlan(
                source = sources.single(),
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
