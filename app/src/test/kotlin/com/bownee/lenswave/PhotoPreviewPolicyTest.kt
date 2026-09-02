package com.bownee.lenswave

import com.bownee.lenswave.gallery.PhotoSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPreviewPolicyTest {
    @Test
    fun currentProtonThumbnailCanBeShown() {
        assertTrue(PhotoPreviewPolicy.canShow(PhotoSource.PROTON, "photo-1", "photo-1", true))
    }

    @Test
    fun staleOrUnavailableThumbnailIsNotShown() {
        assertFalse(PhotoPreviewPolicy.canShow(PhotoSource.PROTON, "photo-1", "photo-2", true))
        assertFalse(PhotoPreviewPolicy.canShow(PhotoSource.PROTON, "photo-1", "photo-1", false))
        assertFalse(PhotoPreviewPolicy.canShow(PhotoSource.DEVICE, "photo-1", "photo-1", true))
    }
}
