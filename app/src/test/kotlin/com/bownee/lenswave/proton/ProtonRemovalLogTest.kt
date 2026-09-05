package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonRemovalLogTest {
    private val log = ProtonRemovalLog()

    @Test
    fun `a removal recorded after the snapshot is subtracted, one recorded before is not`() {
        log.openSnapshot().also { earlier ->
            log.record("user", setOf("before"))
            log.closeSnapshot(earlier)
        }
        val snapshot = log.openSnapshot()
        log.record("user", setOf("after"))

        assertEquals(setOf("after"), log.removedSince("user", snapshot))
        assertEquals(listOf("before", "kept"), log.retain("user", snapshot, listOf("before", "after", "kept")) { it })
    }

    @Test
    fun `removals are kept per user`() {
        val snapshot = log.openSnapshot()
        log.record("other", setOf("x"))

        assertTrue(log.removedSince("user", snapshot).isEmpty())
        assertEquals(setOf("x"), log.removedSince("other", snapshot))
    }

    @Test
    fun `a listing nothing was removed from keeps its instance`() {
        val snapshot = log.openSnapshot()
        val listing = listOf("a", "b")

        assertSame(listing, log.retain("user", snapshot, listing) { it })
        log.record("user", emptySet())
        assertSame(listing, log.retain("user", snapshot, listing) { it })
    }

    @Test
    fun `removals nobody can ask about are not kept`() {
        log.record("user", setOf("unobserved"))
        val snapshot = log.openSnapshot()

        assertTrue(log.removedSince("user", snapshot).isEmpty())
    }

    @Test
    fun `closing the newer snapshot keeps what the older one still needs`() {
        val older = log.openSnapshot()
        log.record("user", setOf("first"))
        val newer = log.openSnapshot()
        log.record("user", setOf("second"))

        log.closeSnapshot(newer)
        assertEquals(setOf("first", "second"), log.removedSince("user", older))

        log.closeSnapshot(older)
        val fresh = log.openSnapshot()
        assertTrue(log.removedSince("user", fresh).isEmpty())
    }

    @Test
    fun `two syncs sharing an epoch release it only when both closed`() {
        val first = log.openSnapshot()
        val second = log.openSnapshot()
        assertEquals(first, second)
        log.record("user", setOf("x"))

        log.closeSnapshot(first)
        assertEquals(setOf("x"), log.removedSince("user", second))
        log.closeSnapshot(second)
        log.closeSnapshot(second)
        assertTrue(log.removedSince("user", second).isEmpty())
    }
}
