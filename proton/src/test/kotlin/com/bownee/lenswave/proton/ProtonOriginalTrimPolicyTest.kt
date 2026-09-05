package com.bownee.lenswave.proton

import com.bownee.lenswave.proton.ProtonOriginalTrimPolicy.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonOriginalTrimPolicyTest {
    private val now = 1_000_000L
    private val partTtl = 24L * 60L * 60L * 1_000L

    @Test
    fun nothingIsDeletedWhileTheDirectoryFits() {
        val entries = listOf(entry("a.image", 100L, now - 5), entry("b.image", 100L, now - 1))

        assertEquals(emptyList<String>(), select(entries, limitBytes = 200L))
    }

    @Test
    fun leastRecentlyUsedOriginalsGoFirstUntilTheDirectoryFits() {
        val entries =
            listOf(
                entry("newest.image", 100L, now - 1),
                entry("oldest.image", 100L, now - 30),
                entry("middle.image", 100L, now - 10),
            )

        assertEquals(listOf("oldest.image", "middle.image"), select(entries, limitBytes = 100L))
        assertEquals(listOf("oldest.image"), select(entries, limitBytes = 250L))
    }

    @Test
    fun theOriginalJustStoredIsNeverDeleted() {
        val entries = listOf(entry("stored.image", 300L, now - 50), entry("other.image", 100L, now - 1))

        assertEquals(listOf("other.image"), select(entries, limitBytes = 200L, keepName = "stored.image"))
    }

    @Test
    fun freshPartialDownloadsAreKeptButCountTowardTheLimit() {
        val entries =
            listOf(
                entry("fresh.image.123.part", 150L, now - 60_000L),
                entry("done.image", 100L, now - 1),
            )

        assertEquals(listOf("done.image"), select(entries, limitBytes = 200L))
    }

    @Test
    fun stalePartialDownloadsAreDeletedBeforeAnyOriginal() {
        val entries =
            listOf(
                entry("done.image", 100L, now - 1),
                entry("abandoned.image.1.part", 150L, now - partTtl - 1L),
                entry("unstamped.image.2.part", 10L, 0L),
            )

        assertEquals(listOf("abandoned.image.1.part", "unstamped.image.2.part"), select(entries, limitBytes = 100L))
        assertEquals(listOf("abandoned.image.1.part", "unstamped.image.2.part"), select(entries, limitBytes = 10_000L))
    }

    @Test
    fun emptyDirectoryNeedsNoWork() {
        assertEquals(emptyList<String>(), select(emptyList(), limitBytes = 0L))
    }

    private fun select(
        entries: List<Entry>,
        limitBytes: Long,
        keepName: String? = null,
    ): List<String> = ProtonOriginalTrimPolicy.select(entries, limitBytes, now, partTtl, keepName)

    private fun entry(
        name: String,
        size: Long,
        lastModified: Long,
    ) = Entry(name, size, lastModified)
}
