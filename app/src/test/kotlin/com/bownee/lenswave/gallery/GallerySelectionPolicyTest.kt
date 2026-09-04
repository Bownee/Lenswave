package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySelectionPolicyTest {
    private val rows =
        listOf(
            GalleryRow.DateHeader("2026-07-12", "Sun, 12 Jul 2026"),
            GalleryRow.Photos(listOf(photo("a"), photo("b"), photo("c"))),
            GalleryRow.Photos(listOf(photo("d"))),
        )

    @Test
    fun `selected photos still listed are not missing`() {
        assertTrue(GallerySelectionPolicy.missingSelection(rows, setOf("a", "d")).isEmpty())
        assertTrue(GallerySelectionPolicy.missingSelection(rows, emptySet()).isEmpty())
    }

    @Test
    fun `selected photos that left the rows are reported`() {
        assertEquals(setOf("x"), GallerySelectionPolicy.missingSelection(rows, listOf("b", "x")))
        assertEquals(setOf("x", "y"), GallerySelectionPolicy.missingSelection(emptyList(), listOf("x", "y")))
    }

    @Test
    fun `the scan stops once every selected id has been seen`() {
        val neverTouched =
            object : AbstractList<GalleryAsset>() {
                override val size: Int get() = 1

                override fun get(index: Int): GalleryAsset = error("scanned past the selection")
            }

        assertTrue(
            GallerySelectionPolicy
                .missingSelection(rows + GalleryRow.Photos(neverTouched), setOf("a", "b"))
                .isEmpty(),
        )
    }

    private fun photo(id: String) =
        GalleryAsset(stableId = id, capturedAtEpochMillis = 1, nodeUid = id, hasThumbnail = true)
}
