package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticFavoriteStateTest {
    @Test
    fun `requested value is displayed immediately and retained until server confirmation`() {
        val state = OptimisticFavoriteState()

        state.begin("photo", favorite = true)

        assertTrue(state.displayedValue("photo", serverState = false))
        assertTrue(state.isUpdating("photo"))

        state.finish("photo", succeeded = true, serverState = false)
        assertTrue(state.displayedValue("photo", serverState = false))
        assertFalse(state.isUpdating("photo"))

        state.reconcile(mapOf("photo" to true))
        assertTrue(state.displayedValue("photo", serverState = true))
    }

    @Test
    fun `failed request rolls back to server value`() {
        val state = OptimisticFavoriteState()
        state.begin("photo", favorite = true)

        state.finish("photo", succeeded = false, serverState = false)

        assertFalse(state.displayedValue("photo", serverState = false))
        assertFalse(state.isUpdating("photo"))
    }
}
