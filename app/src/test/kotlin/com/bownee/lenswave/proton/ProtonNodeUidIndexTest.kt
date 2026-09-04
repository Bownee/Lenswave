package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonNodeUidIndexTest {
    private var extractions = 0
    private val index =
        ProtonNodeUidIndex<Pair<String?, Int>> { item ->
            extractions++
            item.first
        }

    @Test
    fun `answers by node uid and keeps the first of duplicate keys`() {
        val items = listOf("a" to 1, "b" to 2, "a" to 3, null to 4)

        assertEquals(1, index.find(items, "a")?.second)
        assertEquals(2, index.find(items, "b")?.second)
        assertNull(index.find(items, "missing"))
        assertTrue(index.contains(items, "b"))
        assertFalse(index.contains(items, "missing"))
    }

    @Test
    fun `the same list instance is indexed once`() {
        val items = listOf("a" to 1, "b" to 2)

        index.find(items, "a")
        index.find(items, "b")
        index.contains(items, "c")

        assertEquals(items.size, extractions)
    }

    @Test
    fun `a list that only changed item values keeps the memo and answers from the new list`() {
        val before = listOf("a" to 1, "b" to 2, "c" to 3)
        index.find(before, "a")
        val builtOnce = extractions

        val after = before.map { (uid, value) -> uid to value * 10 }

        assertEquals(20, index.find(after, "b")?.second)
        // Reusing costs one comparison pass over both lists; a rebuild would add a full pass more.
        assertEquals(builtOnce + 2 * before.size, extractions)
        assertEquals(30, index.find(after, "c")?.second)
        assertEquals(builtOnce + 2 * before.size, extractions)
    }

    @Test
    fun `a list with different node uids replaces the memo`() {
        index.find(listOf("a" to 1, "b" to 2), "a")

        val reordered = listOf("b" to 2, "a" to 1)
        assertEquals(1, index.find(reordered, "a")?.second)
        assertEquals(2, index.find(reordered, "b")?.second)

        val grown = listOf("b" to 2, "a" to 1, "c" to 3)
        assertEquals(3, index.find(grown, "c")?.second)
        assertEquals(1, index.find(grown, "a")?.second)

        val shrunk = listOf("c" to 3)
        assertNull(index.find(shrunk, "a"))
        assertEquals(3, index.find(shrunk, "c")?.second)
    }
}
