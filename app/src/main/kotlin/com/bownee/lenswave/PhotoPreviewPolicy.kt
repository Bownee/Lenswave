package com.bownee.lenswave

import com.bownee.lenswave.gallery.PhotoSource

internal object PhotoPreviewPolicy {
    fun canShow(
        source: PhotoSource,
        requestedStableId: String,
        currentStableId: String,
        thumbnailDecoded: Boolean,
    ): Boolean = source == PhotoSource.PROTON &&
        thumbnailDecoded &&
        requestedStableId == currentStableId
}
