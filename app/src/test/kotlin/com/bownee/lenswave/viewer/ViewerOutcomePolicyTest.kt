package com.bownee.lenswave.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerOutcomePolicyTest {
    private val window = listOf(request("a"), request("b"), request("c"))

    @Test
    fun `outcomes of the photo on screen and of its window are the viewer's`() {
        assertTrue(ViewerOutcomePolicy.consumes("b", currentStableId = "b", navigationRequests = window))
        assertTrue(ViewerOutcomePolicy.consumes("c", currentStableId = "b", navigationRequests = window))
        // A restored viewer whose window was cut down still owns the photo it shows.
        assertTrue(ViewerOutcomePolicy.consumes("z", currentStableId = "z", navigationRequests = emptyList()))
    }

    @Test
    fun `outcomes of photos the viewer never showed stay queued for the gallery`() {
        assertFalse(ViewerOutcomePolicy.consumes("x", currentStableId = "b", navigationRequests = window))
    }

    private fun request(stableId: String) = PhotoRequest(stableId, "n-$stableId", "u", 0L, "$stableId.jpg")
}
