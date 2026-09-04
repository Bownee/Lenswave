package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
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
}
