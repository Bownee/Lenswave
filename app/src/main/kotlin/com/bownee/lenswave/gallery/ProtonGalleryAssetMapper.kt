package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonMediaTag

fun ProtonGalleryPhoto.toGalleryAsset(
    displayName: String = "",
    mediaKind: MediaKind = MediaKind.IMAGE,
    tags: Set<ProtonMediaTag> = emptySet(),
): GalleryAsset =
    GalleryAsset(
        stableId = "proton:$nodeUid",
        capturedAtEpochMillis = captureTimeEpochSeconds * 1_000L,
        displayName = displayName,
        nodeUid = nodeUid,
        hasThumbnail = hasThumbnail,
        mediaKind = mediaKind,
        tags = tags,
    )
