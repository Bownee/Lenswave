package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSnapshotCoordinatorTest {
    @Test fun everySourceUsesTheSameCommitAndFreshnessTransaction() {
        val metadata = FakeMetadata()
        val clock = FakeClock(1_000L)
        val coordinator = ProtonSnapshotCoordinator(metadata, clock)

        ProtonSyncSource.entries.forEach { source ->
            val key = source.name.lowercase()
            assertTrue(coordinator.shouldEnumerate("user", source, key, false, false))
            coordinator.commit("user", key)
            assertFalse(coordinator.shouldEnumerate("user", source, key, false, true))
            clock.value += source.maximumAgeMillis
            assertTrue(coordinator.shouldEnumerate("user", source, key, false, true))
            clock.value += 1_000L
        }
    }

    private class FakeMetadata : ProtonSyncMetadataStore {
        private val values = mutableMapOf<Pair<String, String>, Long>()
        override fun readLastSuccessfulSync(userId: String, source: String): Long =
            values[userId to source] ?: 0L

        override fun writeLastSuccessfulSync(userId: String, source: String, timestampMillis: Long) {
            values[userId to source] = timestampMillis
        }
    }

    private class FakeClock(var value: Long) : LenswaveClock {
        override fun nowMillis(): Long = value
    }
}
