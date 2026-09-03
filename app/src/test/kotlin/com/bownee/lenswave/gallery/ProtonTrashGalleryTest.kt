package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonTrashPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonTrashGalleryTest {
    @Test
    fun createPhotosMarksProtonItemsAsTrashedAndUsesTrashDate() {
        val photo = ProtonTrashGallery.createPhotos(
            listOf(ProtonTrashPhoto("node-1", 1_234L, hasThumbnail = true, displayName = "photo.jpg"))
        ).single()

        assertEquals("proton-trash:node-1", photo.stableId)
        assertEquals(1_234_000L, photo.capturedAtEpochMillis)
        assertEquals("photo.jpg", photo.displayName)
        assertEquals("node-1", photo.nodeUid)
        assertTrue(photo.hasThumbnail)
        assertTrue(photo.isTrashed)
    }
}
