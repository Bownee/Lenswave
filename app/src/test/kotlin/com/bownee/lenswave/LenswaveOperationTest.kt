package com.bownee.lenswave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LenswaveOperationTest {
    @Test
    fun `operation tags are unique and safe to log`() {
        val tags = LenswaveOperation.entries.map(LenswaveOperation::tag)
        val safe = Regex("[a-z0-9_-]{1,64}")

        assertEquals(tags.size, tags.distinct().size)
        tags.forEach { tag -> assertTrue(tag, safe.matches(tag)) }
        assertEquals(
            "operation=timeline-sync state=running attempt=1 maximumAttempts=1",
            LenswaveDiagnostics.stateSummary(LenswaveOperation.TIMELINE_SYNC.tag, "running", 1, 1),
        )
    }
}
