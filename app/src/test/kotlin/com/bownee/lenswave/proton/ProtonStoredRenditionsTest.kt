package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.AtomicFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonStoredRenditionsTest {
    @Test
    fun answersFromStoredFileNames() {
        val availability =
            ProtonStoredRenditions(
                thumbnailNames = setOf(AtomicFileStore.safeName("with-thumbnail"), AtomicFileStore.safeName("both")),
                previewNames = setOf(AtomicFileStore.safeName("both")),
            )

        assertTrue(availability.hasThumbnail("with-thumbnail"))
        assertFalse(availability.hasPreview("with-thumbnail"))
        assertTrue(availability.hasThumbnail("both"))
        assertTrue(availability.hasPreview("both"))
        assertFalse(availability.hasThumbnail("neither"))
        assertFalse(availability.hasPreview("neither"))
    }

    @Test
    fun hydratesAPhotoWithOneNameDerivation() {
        val derivations = mutableListOf<String>()
        val availability =
            ProtonStoredRenditions(
                thumbnailNames = setOf("name:node"),
                previewNames = setOf("name:node"),
                fileNameOf = { nodeUid ->
                    derivations += nodeUid
                    "name:$nodeUid"
                },
            )

        val photo = availability.photo("node", captureTimeEpochSeconds = 42L)

        assertEquals(ProtonGalleryPhoto("node", 42L, hasThumbnail = true, hasPreview = true), photo)
        assertEquals(listOf("node"), derivations)
    }

    @Test
    fun noneHasNothing() {
        assertEquals(
            ProtonGalleryPhoto("node", 1L, hasThumbnail = false, hasPreview = false),
            ProtonStoredRenditions.NONE.photo("node", 1L),
        )
    }
}
