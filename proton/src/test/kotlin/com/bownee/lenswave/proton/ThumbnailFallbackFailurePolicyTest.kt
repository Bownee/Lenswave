package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailFallbackFailurePolicyTest {
    @Test
    fun missingPreviewLeavesAnUnansweredThumbnailUnanswered() {
        assertEquals(
            ThumbnailFailureKind.UNANSWERED,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.UNANSWERED, ThumbnailFailureKind.NOT_FOUND),
        )
    }

    @Test
    fun missingPreviewLeavesATransientThumbnailFailureTransient() {
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.OTHER, ThumbnailFailureKind.NOT_FOUND),
        )
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFallbackFailurePolicy.settle(null, ThumbnailFailureKind.NOT_FOUND),
        )
    }

    @Test
    fun nodeWithNeitherRenditionIsNotFound() {
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.NOT_FOUND, ThumbnailFailureKind.NOT_FOUND),
        )
    }

    @Test
    fun missingThumbnailStaysNotFoundWhateverThePreviewSaid() {
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.NOT_FOUND, ThumbnailFailureKind.UNANSWERED),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.NOT_FOUND, ThumbnailFailureKind.OTHER),
        )
    }

    @Test
    fun otherPreviewFailuresKeepTheMoreSpecificKind() {
        assertEquals(
            ThumbnailFailureKind.UNANSWERED,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.OTHER, ThumbnailFailureKind.UNANSWERED),
        )
        assertEquals(
            ThumbnailFailureKind.UNANSWERED,
            ThumbnailFallbackFailurePolicy.settle(ThumbnailFailureKind.UNANSWERED, ThumbnailFailureKind.OTHER),
        )
    }
}
