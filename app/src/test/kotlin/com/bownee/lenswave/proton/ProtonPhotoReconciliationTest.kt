package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonPhotoReconciliationTest {
    @Test
    fun reportsCachedRemovalsAndWhetherAnythingWasAdded() {
        val changes =
            ProtonPhotoReconciliation.compare(
                cachedNodeUids = listOf("kept", "removed"),
                remoteNodeUids = setOf("kept", "added"),
            )

        assertEquals(setOf("removed"), changes.removedNodeUids)
        assertTrue(changes.hasAdditions)
        assertFalse(changes.isEmpty)

        val additionsOnly = ProtonPhotoReconciliation.compare(listOf("kept"), setOf("kept", "added"))
        assertTrue(additionsOnly.removedNodeUids.isEmpty())
        assertTrue(additionsOnly.hasAdditions)

        val removalsOnly = ProtonPhotoReconciliation.compare(listOf("kept", "gone"), setOf("kept"))
        assertEquals(setOf("gone"), removalsOnly.removedNodeUids)
        assertFalse(removalsOnly.hasAdditions)
    }

    @Test
    fun ignoresOrderingAndDuplicates() {
        val changes =
            ProtonPhotoReconciliation.compare(
                cachedNodeUids = listOf("a", "b", "b"),
                remoteNodeUids = setOf("b", "a"),
            )

        assertEquals(ProtonPhotoChanges(), changes)
        assertTrue(changes.isEmpty)
    }
}
