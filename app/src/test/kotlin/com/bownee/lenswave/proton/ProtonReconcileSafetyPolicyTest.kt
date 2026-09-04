package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonReconcileSafetyPolicyTest {
    @Test
    fun `a listing that drops most of a large library is refused unless the user asked`() {
        assertFalse(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 1_000, removedCount = 501, forceRemote = false))
        assertFalse(
            ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 1_000, removedCount = 1_000, forceRemote = false),
        )
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 1_000, removedCount = 1_000, forceRemote = true))
    }

    @Test
    fun `half of the library or less is committed`() {
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 1_000, removedCount = 500, forceRemote = false))
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 1_000, removedCount = 0, forceRemote = false))
    }

    @Test
    fun `a small library may lose most of itself`() {
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 199, removedCount = 199, forceRemote = false))
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 300, removedCount = 199, forceRemote = false))
        assertFalse(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 300, removedCount = 200, forceRemote = false))
    }

    @Test
    fun `a first listing over an empty cache is always committed`() {
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(cachedCount = 0, removedCount = 0, forceRemote = false))
    }

    @Test
    fun `large counts do not overflow the share comparison`() {
        assertTrue(ProtonReconcileSafetyPolicy.mayCommit(Int.MAX_VALUE, Int.MAX_VALUE / 2, forceRemote = false))
        assertFalse(ProtonReconcileSafetyPolicy.mayCommit(Int.MAX_VALUE, Int.MAX_VALUE, forceRemote = false))
    }

    @Test
    fun `requireCommit throws for a suspicious listing and names it`() {
        val existing = List(400) { index -> "p$index" }
        val remote = existing.take(100).toSet()

        val error =
            assertThrows(ProtonSuspiciousListingException::class.java) {
                ProtonReconcileSafetyPolicy.requireCommit("albums", existing, remote, forceRemote = false) { it }
            }

        assertEquals(
            "Remote albums listing dropped 300 of 400 cached entries; refusing to reconcile without a manual refresh",
            error.message,
        )
    }

    @Test
    fun `requireCommit passes a forced refresh, an empty cache and a modest change`() {
        val existing = List(400) { index -> "p$index" }

        ProtonReconcileSafetyPolicy.requireCommit("albums", existing, emptySet(), forceRemote = true) { it }
        ProtonReconcileSafetyPolicy.requireCommit("albums", emptyList<String>(), emptySet(), forceRemote = false) { it }
        ProtonReconcileSafetyPolicy.requireCommit(
            "albums",
            existing,
            existing.take(300).toSet(),
            forceRemote = false,
        ) { it }
    }
}
