package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtonProgressiveReadPolicyTest {
    @Test
    fun `an open while the download runs has an unknown length rather than an error`() {
        assertEquals(
            ProtonProgressiveReadPolicy.LENGTH_UNKNOWN,
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = 0L,
                requestedLength = ProtonProgressiveReadPolicy.LENGTH_UNKNOWN,
                availableBytes = 4_096L,
                complete = false,
            ),
        )
    }

    @Test
    fun `a finished file bounds the read by its real length`() {
        assertEquals(
            300L,
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = 700L,
                requestedLength = ProtonProgressiveReadPolicy.LENGTH_UNKNOWN,
                availableBytes = 1_000L,
                complete = true,
            ),
        )
        assertEquals(
            0L,
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = 1_000L,
                requestedLength = ProtonProgressiveReadPolicy.LENGTH_UNKNOWN,
                availableBytes = 1_000L,
                complete = true,
            ),
        )
    }

    @Test
    fun `an explicit request length wins whether or not the download is complete`() {
        assertEquals(
            50L,
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = 10L,
                requestedLength = 50L,
                availableBytes = 20L,
                complete = false,
            ),
        )
    }

    @Test
    fun `a position beyond the finished file is an error`() {
        assertNull(
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = 1_001L,
                requestedLength = ProtonProgressiveReadPolicy.LENGTH_UNKNOWN,
                availableBytes = 1_000L,
                complete = true,
            ),
        )
        assertNull(
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = 0L,
                requestedLength = -5L,
                availableBytes = 1_000L,
                complete = true,
            ),
        )
    }
}
