package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonTrashPhoto
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonThumbnailProgressCalculatorTest {
    @Test
    fun `timeline progress only counts timeline photos`() {
        val progress = ProtonThumbnailProgressCalculator.timeline(
            listOf(
                ProtonGalleryPhoto("ready", 2, hasThumbnail = true),
                ProtonGalleryPhoto("pending", 1, hasThumbnail = false),
            ),
        )

        assertEquals(ProtonThumbnailProgress(downloaded = 1, total = 2), progress)
    }

    @Test
    fun `album progress counts only albums that have covers`() {
        val progress = ProtonThumbnailProgressCalculator.albumCovers(
            listOf(
                ProtonAlbum("ready", "Ready", 1, "cover-1", 1, 2, true, false),
                ProtonAlbum("pending", "Pending", 1, "cover-2", 1, 2, false, false),
                ProtonAlbum("empty", "Empty", 0, null, 1, 2, false, false),
            ),
        )

        assertEquals(ProtonThumbnailProgress(downloaded = 1, total = 2), progress)
    }

    @Test
    fun `trash progress only counts trash photos`() {
        val progress = ProtonThumbnailProgressCalculator.trash(
            listOf(
                ProtonTrashPhoto("ready", 2, hasThumbnail = true),
                ProtonTrashPhoto("pending", 1, hasThumbnail = false),
            ),
        )

        assertEquals(ProtonThumbnailProgress(downloaded = 1, total = 2), progress)
    }
}
