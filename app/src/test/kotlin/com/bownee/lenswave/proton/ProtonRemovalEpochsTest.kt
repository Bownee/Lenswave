package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonRemovalEpochsTest {
    private val epochs = ProtonRemovalEpochs()

    @Test
    fun `a commit that started before a removal is refused and does not run`() {
        val startedIn = epochs.current("u:n")
        var removed = false
        var committed = false

        epochs.remove("u:n") { removed = true }

        assertTrue(removed)
        assertFalse(epochs.commitIf("u:n", startedIn) { committed = true })
        assertFalse(committed)
    }

    @Test
    fun `a commit with no removal in between runs once`() {
        val startedIn = epochs.current("u:n")
        var commits = 0

        assertTrue(epochs.commitIf("u:n", startedIn) { commits++ })

        assertEquals(1, commits)
        // Nothing was removed since, so a later commit of the same epoch is still welcome.
        assertTrue(epochs.commitIf("u:n", startedIn) { commits++ })
        assertEquals(2, commits)
    }

    @Test
    fun `work started after the removal commits and other nodes are unaffected`() {
        val otherStartedIn = epochs.current("u:other")
        epochs.remove("u:n") {}
        val startedAfter = epochs.current("u:n")

        assertTrue(epochs.commitIf("u:n", startedAfter) {})
        assertTrue(epochs.commitIf("u:other", otherStartedIn) {})
        assertEquals(1L, startedAfter)
    }

    @Test
    fun `remove returns what the removal produced`() {
        assertEquals("deleted", epochs.remove("u:n") { "deleted" })
    }
}
