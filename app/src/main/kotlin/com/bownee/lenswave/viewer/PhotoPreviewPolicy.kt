package com.bownee.lenswave.viewer

internal object PhotoPreviewPolicy {
    fun canShow(
        requestedStableId: String,
        currentStableId: String,
        thumbnailDecoded: Boolean,
    ): Boolean = thumbnailDecoded && requestedStableId == currentStableId
}
