package com.bownee.lenswave

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
}
