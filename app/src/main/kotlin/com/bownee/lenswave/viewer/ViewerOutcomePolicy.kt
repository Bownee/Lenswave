package com.bownee.lenswave.viewer

/**
 * Which queued mutation outcomes a viewer takes for itself. The coordinator outlives the viewer,
 * so its queue can hold outcomes of photos this viewer never showed: a call started by an
 * earlier viewer that finished after it left, say. Those stay queued for the gallery, which is
 * the terminal consumer of whatever no viewer reported; taking them here would hide the change
 * behind a viewer whose result the gallery may never see (a back press finishes with
 * RESULT_CANCELED once the outcome has been consumed and the result lost).
 */
internal object ViewerOutcomePolicy {
    /** True when [outcomeStableId] is the photo on screen or one of the window the viewer can swipe to. */
    fun consumes(
        outcomeStableId: String,
        currentStableId: String,
        navigationRequests: List<PhotoRequest>,
    ): Boolean = outcomeStableId == currentStableId || navigationRequests.any { it.stableId == outcomeStableId }
}
