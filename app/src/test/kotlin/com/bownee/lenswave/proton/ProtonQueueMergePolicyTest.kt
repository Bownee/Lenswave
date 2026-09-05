package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProtonQueueMergePolicyTest {
    @Test
    fun `stored entries memory never saw are kept after the in-memory ones`() {
        val inMemory = listOf(entry("a", retryCount = 0), entry("b", retryCount = 0))
        val stored = listOf(entry("a", retryCount = 3), entry("c", retryCount = 1))

        val merged = ProtonQueueMergePolicy.merge(inMemory, stored)

        assertEquals(listOf("a", "b", "c"), merged.map(ProtonThumbnailQueueEntry::nodeUid))
        assertEquals(0, merged.first { it.nodeUid == "a" }.retryCount)
        assertEquals(1, merged.first { it.nodeUid == "c" }.retryCount)
    }

    @Test
    fun `nothing stored leaves the in-memory list as it is`() {
        val inMemory = listOf(entry("a", retryCount = 0))

        assertSame(inMemory, ProtonQueueMergePolicy.merge(inMemory, emptyList()))
    }

    @Test
    fun `an empty memory takes the stored entries whole`() {
        val stored = listOf(entry("x", retryCount = 2))

        assertEquals(stored, ProtonQueueMergePolicy.merge(emptyList(), stored))
    }

    private fun entry(
        nodeUid: String,
        retryCount: Int,
    ) = ProtonThumbnailQueueEntry(
        nodeUid = nodeUid,
        sourceCaptureTimes = mapOf(ProtonSyncKeys.QueueSource.TIMELINE to 1L),
        retryCount = retryCount,
    )
}
