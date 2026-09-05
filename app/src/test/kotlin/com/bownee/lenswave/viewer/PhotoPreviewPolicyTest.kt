package com.bownee.lenswave.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPreviewPolicyTest {
    @Test
    fun currentThumbnailCanBeShown() {
        assertTrue(PhotoPreviewPolicy.canShow("photo-1", "photo-1", true))
    }

    @Test
    fun staleOrUnavailableThumbnailIsNotShown() {
        assertFalse(PhotoPreviewPolicy.canShow("photo-1", "photo-2", true))
        assertFalse(PhotoPreviewPolicy.canShow("photo-1", "photo-1", false))
    }

    @Test
    fun aLateStandInIsWantedOnlyWhileNothingBetterIsUp() {
        assertTrue(PhotoPreviewPolicy.wantsStandIn(mediaReady = false, failureShown = false))
        // The original is on screen: the stand-in would hide it.
        assertFalse(PhotoPreviewPolicy.wantsStandIn(mediaReady = true, failureShown = false))
        // The retry panel is up: installing the stand-in hides the panel it shares.
        assertFalse(PhotoPreviewPolicy.wantsStandIn(mediaReady = false, failureShown = true))
    }
}
