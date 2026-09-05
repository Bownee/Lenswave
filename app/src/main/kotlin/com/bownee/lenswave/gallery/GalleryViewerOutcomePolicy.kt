package com.bownee.lenswave.gallery

import com.bownee.lenswave.viewer.ViewerMutationCoordinator

/**
 * What the gallery does with viewer mutation outcomes nobody consumed. The viewer applies the
 * outcomes of its own requests while it is on screen; one that lands after the user has backed
 * out would otherwise wait in the coordinator until the next viewer opened. The gallery is the
 * terminal consumer: once it is resumed with no viewer on top, it takes every pending outcome,
 * refreshes for the successful ones and reports the failed ones.
 */
internal object GalleryViewerOutcomePolicy {
    data class Summary(
        val refresh: Boolean,
        val failedFavorite: Boolean,
        val failedTrash: Boolean,
    )

    /** Only a resumed gallery with no viewer launched from it takes over; the viewer owns the rest. */
    fun consumesNow(
        viewerLaunched: Boolean,
        outcomes: List<ViewerMutationCoordinator.Outcome>,
    ): Boolean = !viewerLaunched && outcomes.isNotEmpty()

    fun summarize(outcomes: List<ViewerMutationCoordinator.Outcome>): Summary {
        var refresh = false
        var failedFavorite = false
        var failedTrash = false
        outcomes.forEach { outcome ->
            when (outcome) {
                is ViewerMutationCoordinator.Outcome.FavoriteSet -> {
                    if (outcome.succeeded) refresh = true else failedFavorite = true
                }

                is ViewerMutationCoordinator.Outcome.Trashed -> {
                    if (outcome.succeeded) refresh = true else failedTrash = true
                }
            }
        }
        return Summary(refresh = refresh, failedFavorite = failedFavorite, failedTrash = failedTrash)
    }
}
