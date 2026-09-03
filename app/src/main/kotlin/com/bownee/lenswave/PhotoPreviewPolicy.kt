package com.bownee.lenswave

internal object PhotoPreviewPolicy {
    fun canShow(
        requestedStableId: String,
        currentStableId: String,
        thumbnailDecoded: Boolean,
    ): Boolean = thumbnailDecoded && requestedStableId == currentStableId
}
