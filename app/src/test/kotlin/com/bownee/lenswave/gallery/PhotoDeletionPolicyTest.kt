package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDeletionPolicyTest {
    @Test
    fun `active photos are moved to the matching source trash`() {
        val decision = PhotoDeletionPolicy.decide(listOf(deviceTarget("one")))

        val plan = (decision as PhotoDeletionDecision.Allowed).plan
        assertEquals(PhotoSource.DEVICE, plan.source)
        assertEquals(PhotoDeletionOperation.MOVE_TO_TRASH, plan.operation)
        assertEquals("content://media/one", (plan.targets.single() as PhotoTarget.Device).uri)
    }

    @Test
    fun `trashed photos are deleted permanently`() {
        val decision = PhotoDeletionPolicy.decide(listOf(protonTarget("one", trashed = true)))

        val plan = (decision as PhotoDeletionDecision.Allowed).plan
        assertEquals(PhotoDeletionOperation.DELETE_PERMANENTLY, plan.operation)
        assertEquals("one", (plan.targets.single() as PhotoTarget.Proton).nodeUid)
    }

    @Test
    fun `mixed primary sources require an explicit user choice`() {
        val decision = PhotoDeletionPolicy.decide(listOf(deviceTarget("one"), protonTarget("two")))

        assertTrue(decision is PhotoDeletionDecision.MixedSources)
    }

    private fun deviceTarget(id: String) = PhotoTarget.Device(
        stableId = id,
        uri = "content://media/$id",
        isTrashed = false,
    )

    private fun protonTarget(id: String, trashed: Boolean = false) = PhotoTarget.Proton(
        stableId = id,
        nodeUid = id,
        isTrashed = trashed,
    )
}
