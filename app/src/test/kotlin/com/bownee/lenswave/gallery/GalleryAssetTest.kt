package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonMediaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAssetTest {
    @Test
    fun `favorite state is carried by the favorites tag`() {
        val favorite = asset().withFavorite(true)
        val restored = favorite.withFavorite(false)

        assertTrue(favorite.isFavorite)
        assertEquals(setOf(ProtonMediaTag.FAVORITES, ProtonMediaTag.VIDEOS), favorite.tags)
        assertFalse(restored.isFavorite)
        assertEquals(setOf(ProtonMediaTag.VIDEOS), restored.tags)
    }

    private fun asset() =
        GalleryAsset(
            stableId = "proton:node",
            capturedAtEpochMillis = 1,
            nodeUid = "node",
            hasThumbnail = true,
            tags = setOf(ProtonMediaTag.VIDEOS),
        )
}
