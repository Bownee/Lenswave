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
    fun trackerStartsDeterminateAndAdvancesFromThePendingQueue() {
        val tracker = ProtonThumbnailNotificationProgressTracker(initialRemaining = 100)

        assertEquals(ProtonThumbnailNotificationProgress(0, 100), tracker.current)
        assertEquals(ProtonThumbnailNotificationProgress(4, 100), tracker.update(96))
        assertEquals(ProtonThumbnailNotificationProgress(100, 100), tracker.update(0))
    }

    @Test
    fun trackerExpandsTheTotalWhenNewWorkIsQueued() {
        val tracker = ProtonThumbnailNotificationProgressTracker(initialRemaining = 10)

        tracker.update(6)

        assertEquals(ProtonThumbnailNotificationProgress(4, 13), tracker.update(9))
    }
}
