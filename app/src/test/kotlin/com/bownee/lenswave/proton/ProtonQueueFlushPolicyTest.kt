package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonQueueFlushPolicyTest {
    @Test
    fun `the first change schedules a delayed write`() {
        assertEquals(
            ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS,
            ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = 1, flushScheduled = false),
        )
    }

    @Test
    fun `further changes ride on the scheduled write`() {
        assertNull(ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = 5, flushScheduled = true))
    }

    @Test
    fun `nothing to write means nothing to schedule`() {
        assertNull(ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = 0, flushScheduled = false))
        assertNull(ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = -1, flushScheduled = true))
    }

    @Test
    fun `enough changes bring the write forward even when one is scheduled`() {
        val limit = ProtonQueueFlushPolicy.MAX_UNFLUSHED_CHANGES
        assertEquals(0L, ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = limit, flushScheduled = true))
        assertEquals(0L, ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = limit + 1, flushScheduled = false))
        assertEquals(
            ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS,
            ProtonQueueFlushPolicy.flushDelayMillis(unflushedChanges = limit - 1, flushScheduled = false),
        )
    }

    @Test
    fun `a snapshot is stale unless it is newer than what was written`() {
        assertTrue(ProtonQueueFlushPolicy.isStale(snapshotGeneration = 3, writtenGeneration = 3))
        assertTrue(ProtonQueueFlushPolicy.isStale(snapshotGeneration = 2, writtenGeneration = 3))
        assertFalse(ProtonQueueFlushPolicy.isStale(snapshotGeneration = 4, writtenGeneration = 3))
    }
}
