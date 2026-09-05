package com.bownee.lenswave.gallery

import com.bownee.lenswave.viewer.ViewerMutationCoordinator.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryViewerOutcomePolicyTest {
    @Test
    fun `the gallery consumes only when resumed without a viewer on top and something is pending`() {
        val pending = listOf<Outcome>(Outcome.Trashed("a", succeeded = true))

        assertTrue(GalleryViewerOutcomePolicy.consumesNow(viewerLaunched = false, pending))
        assertFalse(GalleryViewerOutcomePolicy.consumesNow(viewerLaunched = true, pending))
        assertFalse(GalleryViewerOutcomePolicy.consumesNow(viewerLaunched = false, emptyList()))
    }

    @Test
    fun `successful outcomes ask for a refresh and failed ones are reported by kind`() {
        assertEquals(
            GalleryViewerOutcomePolicy.Summary(refresh = true, failedFavorite = false, failedTrash = false),
            GalleryViewerOutcomePolicy.summarize(
                listOf(
                    Outcome.FavoriteSet("a", favorite = true, succeeded = true),
                    Outcome.Trashed("b", succeeded = true),
                ),
            ),
        )
        assertEquals(
            GalleryViewerOutcomePolicy.Summary(refresh = false, failedFavorite = true, failedTrash = true),
            GalleryViewerOutcomePolicy.summarize(
                listOf(
                    Outcome.FavoriteSet("a", favorite = false, succeeded = false),
                    Outcome.Trashed("b", succeeded = false),
                ),
            ),
        )
        assertEquals(
            GalleryViewerOutcomePolicy.Summary(refresh = false, failedFavorite = false, failedTrash = false),
            GalleryViewerOutcomePolicy.summarize(emptyList()),
        )
    }
}
