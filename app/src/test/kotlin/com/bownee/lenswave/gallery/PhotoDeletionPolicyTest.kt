package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDeletionPolicyTest {
    @Test
    fun `active photos are moved to trash`() {
        val decision = PhotoDeletionPolicy.decide(listOf(target("one")))

        val plan = (decision as PhotoDeletionDecision.Allowed).plan
        assertEquals(PhotoDeletionOperation.MOVE_TO_TRASH, plan.operation)
        assertEquals("one", plan.targets.single().nodeUid)
    }

    @Test
    fun `trashed photos are deleted permanently`() {
        val decision = PhotoDeletionPolicy.decide(listOf(target("one", trashed = true)))

        val plan = (decision as PhotoDeletionDecision.Allowed).plan
        assertEquals(PhotoDeletionOperation.DELETE_PERMANENTLY, plan.operation)
    }

    @Test
    fun `nothing selected is not a deletion`() {
        assertTrue(PhotoDeletionPolicy.decide(emptyList()) is PhotoDeletionDecision.Empty)
    }

    private fun target(id: String, trashed: Boolean = false) = PhotoTarget(
        stableId = id,
        nodeUid = id,
        isTrashed = trashed,
    )
}
