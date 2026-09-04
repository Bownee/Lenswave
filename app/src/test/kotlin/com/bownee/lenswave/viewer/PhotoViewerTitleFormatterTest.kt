package com.bownee.lenswave.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class PhotoViewerTitleFormatterTest {
    @Test
    fun `formats capture date and 24 hour time`() {
        assertEquals(
            "Sun, 12 Jul 2026 · 14:35",
            PhotoViewerTitleFormatter.format(
                capturedAtEpochMillis = 1_783_866_900_000L,
                zoneId = ZoneOffset.UTC,
                locale = Locale.US,
                use24HourTime = true,
            ),
        )
    }

    @Test
    fun `formats capture date and 12 hour time`() {
        assertEquals(
            "Sun, 12 Jul 2026 · 2:35 PM",
            PhotoViewerTitleFormatter.format(
                capturedAtEpochMillis = 1_783_866_900_000L,
                zoneId = ZoneOffset.UTC,
                locale = Locale.US,
                use24HourTime = false,
            ),
        )
    }

    @Test
    fun `hides title when capture time is unavailable`() {
        assertNull(
            PhotoViewerTitleFormatter.format(
                capturedAtEpochMillis = 0L,
                zoneId = ZoneOffset.UTC,
                locale = Locale.US,
                use24HourTime = true,
            ),
        )
    }
}
