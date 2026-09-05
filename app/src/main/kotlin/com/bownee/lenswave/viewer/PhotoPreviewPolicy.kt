package com.bownee.lenswave.viewer

internal object PhotoPreviewPolicy {
    fun canShow(
        requestedStableId: String,
        currentStableId: String,
        thumbnailDecoded: Boolean,
    ): Boolean = thumbnailDecoded && requestedStableId == currentStableId

    /**
     * Whether a thumbnail or preview that arrives late still has a place on screen. Once the
     * full picture is up it would only hide it, and over the failure panel it would hide the
     * retry button and message, which share the loading panel it clears.
     */
    fun wantsStandIn(
        mediaReady: Boolean,
        failureShown: Boolean,
    ): Boolean = !mediaReady && !failureShown
}
