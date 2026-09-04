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
    fun previewsAreOnlyAnnouncedOnceEveryThumbnailIsStored() {
        assertEquals(
            ProtonDownloadPhase.THUMBNAILS,
            ProtonThumbnailNotificationPolicy.phase(thumbnailsPending = 3, previewsPending = 40),
        )
        assertEquals(
            ProtonDownloadPhase.PREVIEWS,
            ProtonThumbnailNotificationPolicy.phase(thumbnailsPending = 0, previewsPending = 40),
        )
        assertEquals(
            ProtonDownloadPhase.THUMBNAILS,
            ProtonThumbnailNotificationPolicy.phase(thumbnailsPending = 0, previewsPending = 0),
        )
    }

    @Test
    fun previewPhaseReportsPreviewCounts() {
        val progress =
            ProtonThumbnailWorkProgress(stored = 500, pending = 0, previewsStored = 120, previewsPending = 380)

        assertEquals(
            ProtonThumbnailNotificationProgress(downloaded = 120, total = 500, phase = ProtonDownloadPhase.PREVIEWS),
            progress.notificationProgress(),
        )
        assertTrue(progress.hasPendingWork)
    }

    @Test
    fun pendingThumbnailsKeepThumbnailCountsEvenWhenPreviewsWait() {
        val progress =
            ProtonThumbnailWorkProgress(stored = 10, pending = 5, previewsStored = 0, previewsPending = 15)

        assertEquals(ProtonThumbnailNotificationProgress(downloaded = 10, total = 15), progress.notificationProgress())
    }

    @Test
    fun workIsCompleteOnlyWhenBothQueuesAreEmpty() {
        assertFalse(ProtonThumbnailWorkProgress(stored = 1, pending = 0).hasPendingWork)
        assertTrue(ProtonThumbnailWorkProgress(stored = 1, pending = 0, previewsPending = 1).hasPendingWork)
        assertTrue(ProtonThumbnailWorkProgress(stored = 1, pending = 1).hasPendingWork)
    }

    @Test
    fun storedProgressContinuesAcrossWorkerRestarts() {
        val beforeRestart = ProtonThumbnailWorkProgress(stored = 800, pending = 1_191)
        val afterRestart = ProtonThumbnailWorkProgress(stored = 800, pending = 1_191)

        assertEquals(beforeRestart.notificationProgress(), afterRestart.notificationProgress())
    }
}
