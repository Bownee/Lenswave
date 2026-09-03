package com.bownee.lenswave.proton

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
}
