package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtonQueueSelectionPolicyTest {
    @Test
    fun `takes the smallest entries in order`() {
        val selected = ProtonQueueSelectionPolicy.takeFirst(listOf(9, 3, 7, 1, 8, 2), limit = 3, order = naturalOrder())

        assertEquals(listOf(1, 2, 3), selected)
    }

    @Test
    fun `returns everything in order when fewer than the limit are offered`() {
        val selected = ProtonQueueSelectionPolicy.takeFirst(listOf(5, 4), limit = 3, order = naturalOrder())

        assertEquals(listOf(4, 5), selected)
    }

    @Test
    fun `honours a descending order and ties`() {
        val selected =
            ProtonQueueSelectionPolicy.takeFirst(
                listOf("b" to 1, "a" to 3, "c" to 3, "d" to 2),
                limit = 3,
                order = compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first },
            )

        assertEquals(listOf("a" to 3, "c" to 3, "d" to 2), selected)
    }

    @Test
    fun `an empty input selects nothing`() {
        assertEquals(
            emptyList<Int>(),
            ProtonQueueSelectionPolicy.takeFirst(emptyList<Int>(), limit = 4, order = naturalOrder()),
        )
    }

    @Test
    fun `the limit must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtonQueueSelectionPolicy.takeFirst(listOf(1), limit = 0, order = naturalOrder())
        }
    }
}
