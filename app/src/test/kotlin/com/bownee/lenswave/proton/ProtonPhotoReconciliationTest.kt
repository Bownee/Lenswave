package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonPhotoReconciliationTest {
    @Test
    fun reportsRemoteAdditionsAndCachedRemovals() {
        val changes =
            ProtonPhotoReconciliation.compare(
                cachedNodeUids = listOf("kept", "removed"),
                remoteNodeUids = listOf("kept", "added"),
            )

        assertEquals(setOf("added"), changes.addedNodeUids)
        assertEquals(setOf("removed"), changes.removedNodeUids)
    }

    @Test
    fun ignoresOrderingAndDuplicates() {
        val changes =
            ProtonPhotoReconciliation.compare(
                cachedNodeUids = listOf("a", "b", "b"),
                remoteNodeUids = listOf("b", "a"),
            )

        assertEquals(ProtonPhotoChanges(), changes)
    }
}
