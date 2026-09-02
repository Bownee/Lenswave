package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto

fun ProtonGalleryPhoto.toGalleryAsset(displayName: String = ""): GalleryAsset = GalleryAsset.proton(
    stableId = "proton:$nodeUid",
    capturedAtEpochMillis = captureTimeEpochSeconds * 1_000L,
    displayName = displayName,
    nodeUid = nodeUid,
    hasThumbnail = hasThumbnail,
)
