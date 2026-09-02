package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonTrashPhoto
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonThumbnailProgressCalculatorTest {
    @Test
    fun `progress combines sources and counts shared nodes once`() {
        val progress = ProtonThumbnailProgressCalculator.calculate(
            timeline = listOf(
                ProtonGalleryPhoto("timeline", 1, hasThumbnail = true),
                ProtonGalleryPhoto("shared", 2, hasThumbnail = false),
            ),
            albums = listOf(
                ProtonAlbum("album", "Trip", 1, "cover", 1, 2, false, false),
            ),
            trash = listOf(
                ProtonTrashPhoto("shared", 3, hasThumbnail = true),
            ),
        )

        assertEquals(ProtonThumbnailProgress(downloaded = 2, total = 3), progress)
    }
}
