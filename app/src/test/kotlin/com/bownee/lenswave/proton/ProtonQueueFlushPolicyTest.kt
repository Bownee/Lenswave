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
    fun `only every Nth batch forces a write`() {
        val interval = ProtonQueueFlushPolicy.BATCHES_PER_FORCED_FLUSH
        assertFalse(ProtonQueueFlushPolicy.shouldFlushAfterBatch(1))
        assertFalse(ProtonQueueFlushPolicy.shouldFlushAfterBatch(interval - 1))
        assertTrue(ProtonQueueFlushPolicy.shouldFlushAfterBatch(interval))
        assertTrue(ProtonQueueFlushPolicy.shouldFlushAfterBatch(interval + 3))
    }

    @Test
    fun `a failed write is retried with backoff up to the cap`() {
        assertEquals(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS, ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(1))
        assertEquals(
            ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS * 2,
            ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(2),
        )
        assertEquals(
            ProtonQueueFlushPolicy.MAX_WRITE_RETRY_DELAY_MILLIS,
            ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(20),
        )
        assertEquals(
            ProtonQueueFlushPolicy.MAX_WRITE_RETRY_DELAY_MILLIS,
            ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(Int.MAX_VALUE),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a retry needs a failure`() {
        ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(0)
    }

    @Test
    fun `a streak of failed writes is reported once`() {
        assertTrue(ProtonQueueFlushPolicy.shouldReportWriteFailure(1))
        assertFalse(ProtonQueueFlushPolicy.shouldReportWriteFailure(2))
        assertFalse(ProtonQueueFlushPolicy.shouldReportWriteFailure(9))
    }

    @Test
    fun `a snapshot is stale unless it is newer than what was written`() {
        assertTrue(ProtonQueueFlushPolicy.isStale(snapshotGeneration = 3, writtenGeneration = 3))
        assertTrue(ProtonQueueFlushPolicy.isStale(snapshotGeneration = 2, writtenGeneration = 3))
        assertFalse(ProtonQueueFlushPolicy.isStale(snapshotGeneration = 4, writtenGeneration = 3))
    }
}
