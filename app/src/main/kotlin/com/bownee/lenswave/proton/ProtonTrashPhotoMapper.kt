package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.MediaKind
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.PhotoNode

internal fun Node.toProtonTrashPhoto(hasThumbnail: Boolean): ProtonTrashPhoto? {
    val mediaType = when (this) {
        is PhotoNode -> mediaType
        is FileNode -> mediaType
        else -> return null
    }
    val mediaKind = when {
        mediaType.startsWith("image/", ignoreCase = true) -> MediaKind.IMAGE
        mediaType.startsWith("video/", ignoreCase = true) -> MediaKind.VIDEO
        else -> return null
    }

    return ProtonTrashPhoto(
        nodeUid = uid.value,
        trashedAtEpochSeconds = (trashTime ?: creationTime).epochSecond,
        hasThumbnail = hasThumbnail,
        displayName = originalFileName().orEmpty(),
        captureTimeEpochSeconds = when (this) {
            is PhotoNode -> captureTime.epochSecond
            else -> creationTime.epochSecond
        },
        mediaKind = mediaKind,
    )
}
