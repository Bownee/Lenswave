package com.bownee.lenswave.proton

import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.PhotoNode

internal fun Node.toProtonTrashPhoto(hasThumbnail: Boolean): ProtonTrashPhoto? {
    val mediaType = when (this) {
        is PhotoNode -> mediaType
        is FileNode -> mediaType
        else -> return null
    }
    if (!mediaType.startsWith("image/", ignoreCase = true)) return null

    return ProtonTrashPhoto(
        nodeUid = uid.value,
        trashedAtEpochSeconds = (trashTime ?: creationTime).epochSecond,
        hasThumbnail = hasThumbnail,
        displayName = originalFileName().orEmpty(),
        captureTimeEpochSeconds = when (this) {
            is PhotoNode -> captureTime.epochSecond
            else -> creationTime.epochSecond
        },
    )
}
