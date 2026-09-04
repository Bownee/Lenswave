package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
