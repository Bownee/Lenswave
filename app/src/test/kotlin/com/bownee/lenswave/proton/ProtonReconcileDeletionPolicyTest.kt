package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonReconcileDeletionPolicyTest {
    @Test
    fun `removed photos no other listing names lose their renditions`() {
        assertEquals(
            listOf("gone"),
            ProtonReconcileDeletionPolicy.deletable(listOf("gone", "in-album"), setOf("in-album", "cover")),
        )
    }

    @Test
    fun `nothing is deleted while the other listings cannot be read`() {
        assertEquals(emptyList<String>(), ProtonReconcileDeletionPolicy.deletable(listOf("gone", "in-album"), null))
    }

    @Test
    fun `an empty reference set deletes every removed photo`() {
        assertEquals(listOf("a", "b"), ProtonReconcileDeletionPolicy.deletable(listOf("a", "b"), emptySet()))
    }
}
