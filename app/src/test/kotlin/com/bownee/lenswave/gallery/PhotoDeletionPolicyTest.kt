package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDeletionPolicyTest {
    @Test
    fun `photos are moved to trash`() {
        val decision = PhotoDeletionPolicy.decide(listOf(target("one")))

        val plan = (decision as PhotoDeletionDecision.Allowed).plan
        assertEquals(PhotoDeletionOperation.MOVE_TO_TRASH, plan.operation)
        assertEquals("one", plan.targets.single().nodeUid)
    }

    @Test
    fun `nothing selected is not a deletion`() {
        assertTrue(PhotoDeletionPolicy.decide(emptyList()) is PhotoDeletionDecision.Empty)
    }

    private fun target(id: String) = PhotoTarget(stableId = id, nodeUid = id)
}
