package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailRunGuardTest {
    @Test
    fun `only one run is admitted at a time`() {
        val guard = ProtonThumbnailRunGuard()

        assertFalse(guard.isActive)
        assertTrue(guard.tryBegin())
        assertTrue(guard.isActive)
        assertFalse(guard.tryBegin())

        guard.end()
        assertFalse(guard.isActive)
        assertTrue(guard.tryBegin())
    }
}
