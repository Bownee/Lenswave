package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailNotificationProgressTest {
    @Test
    fun positiveTotalUsesDeterminateProgress() {
        assertTrue(ProtonThumbnailNotificationProgress(downloaded = 25, total = 100).isDeterminate)
    }

    @Test
    fun missingTimelineTotalUsesIndeterminateProgress() {
        assertFalse(ProtonThumbnailNotificationProgress(downloaded = 0, total = 0).isDeterminate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun downloadedCountCannotExceedTotal() {
        ProtonThumbnailNotificationProgress(downloaded = 101, total = 100)
    }

    @Test
    fun workProgressUsesThePersistentStoredCount() {
        val progress = ProtonThumbnailWorkProgress(stored = 800, pending = 1_191)

        assertEquals(
            ProtonThumbnailNotificationProgress(downloaded = 800, total = 1_991),
            progress.notificationProgress(),
        )
        assertEquals(1_191, progress.notificationProgress().remaining)
    }

    @Test
    fun storedProgressContinuesAcrossWorkerRestarts() {
        val beforeRestart = ProtonThumbnailWorkProgress(stored = 800, pending = 1_191)
        val afterRestart = ProtonThumbnailWorkProgress(stored = 800, pending = 1_191)

        assertEquals(beforeRestart.notificationProgress(), afterRestart.notificationProgress())
    }
}
