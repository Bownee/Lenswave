package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProtonNodeUidIndexTest {
    private val index = ProtonNodeUidIndex<Pair<String?, Int>> { item -> item.first }

    @Test
    fun `answers by node uid and keeps the first of duplicate keys`() {
        val lookup = index.of(listOf("a" to 1, "b" to 2, "a" to 3, null to 4))

        assertEquals(1, lookup["a"]?.second)
        assertEquals(2, lookup["b"]?.second)
        assertNull(lookup["missing"])
        assertEquals(2, lookup.size)
    }

    @Test
    fun `the same list instance is indexed once`() {
        val items = listOf("a" to 1)

        assertSame(index.of(items), index.of(items))
    }

    @Test
    fun `a new list instance replaces the memo`() {
        val first = index.of(listOf("a" to 1))
        val second = index.of(listOf("a" to 1, "b" to 2))

        assertNotSame(first, second)
        assertEquals(2, second.size)
    }
}
