package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProtonNewestFirstListingTest {
    @Test
    fun insertsEachAdditionWhereItsCaptureTimeBelongs() {
        val sorted = listOf(photo("d", 400L), photo("b", 200L))

        val result =
            ProtonNewestFirstListing.insert(
                sorted,
                listOf(photo("a", 100L), photo("e", 500L), photo("c", 300L)),
            )

        assertEquals(listOf("e", "d", "c", "b", "a"), result.map(ProtonGalleryPhoto::nodeUid))
    }

    @Test
    fun skipsAdditionsAlreadyListedAndKeepsTheInstanceWhenNothingIsInserted() {
        val sorted = listOf(photo("d", 400L), photo("b", 200L))

        assertSame(sorted, ProtonNewestFirstListing.insert(sorted, listOf(photo("d", 400L))))
        assertSame(sorted, ProtonNewestFirstListing.insert(sorted, emptyList()))
        assertEquals(
            listOf("d", "b", "x"),
            ProtonNewestFirstListing
                .insert(
                    sorted,
                    listOf(photo("b", 200L), photo("x", 0L)),
                ).map(ProtonGalleryPhoto::nodeUid),
        )
    }

    @Test
    fun anEqualCaptureTimeGoesInFrontOfTheOnesAlreadyListed() {
        val sorted = listOf(photo("d", 400L), photo("b", 200L))

        val result = ProtonNewestFirstListing.insert(sorted, listOf(photo("b2", 200L)))

        assertEquals(listOf("d", "b2", "b"), result.map(ProtonGalleryPhoto::nodeUid))
    }

    private fun photo(
        nodeUid: String,
        captureTime: Long,
    ) = ProtonGalleryPhoto(nodeUid = nodeUid, captureTimeEpochSeconds = captureTime, hasThumbnail = false)
}
