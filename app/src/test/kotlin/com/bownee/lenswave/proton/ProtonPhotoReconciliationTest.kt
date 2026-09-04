package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun dropsPhotosRemovedWhileEnumeratingButKeepsRemoteAdditions() {
        val narrowed =
            ProtonPhotoReconciliation.withoutRemovedSince(
                enumerated = listOf("kept", "trashed", "added"),
                existing = listOf("kept", "trashed"),
                published = listOf("kept"),
            ) { it }

        assertEquals(listOf("kept", "added"), narrowed)
    }

    @Test
    fun keepsTheListingInstanceWhenNothingWasRemovedOrNothingIsPublished() {
        val enumerated = listOf("kept", "added")

        assertSame(
            enumerated,
            ProtonPhotoReconciliation.withoutRemovedSince(enumerated, listOf("kept"), listOf("kept")) { it },
        )
        assertSame(enumerated, ProtonPhotoReconciliation.withoutRemovedSince(enumerated, listOf("kept"), null) { it })
        assertSame(
            enumerated,
            ProtonPhotoReconciliation.withoutRemovedSince(enumerated, listOf("kept"), listOf("kept", "marked")) { it },
        )
    }
}
