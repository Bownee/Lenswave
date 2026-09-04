package com.bownee.lenswave.viewer

import com.bownee.lenswave.proton.ProtonOriginalDownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerVideoProgressPolicyTest {
    @Test
    fun completeDownloadIsPreparing() {
        val display =
            ViewerVideoProgressPolicy.display(
                ProtonOriginalDownloadProgress(downloadedBytes = 10, totalBytes = 10, complete = true),
            )

        assertEquals(ViewerVideoProgressPolicy.Display.Preparing, display)
    }

    @Test
    fun unknownTotalShowsOnlyDownloadedBytes() {
        val display =
            ViewerVideoProgressPolicy.display(
                ProtonOriginalDownloadProgress(downloadedBytes = 4_096, totalBytes = null),
            )

        assertEquals(ViewerVideoProgressPolicy.Display.Unsized(4_096), display)
    }

    @Test
    fun zeroTotalCountsAsUnknown() {
        val display =
            ViewerVideoProgressPolicy.display(
                ProtonOriginalDownloadProgress(downloadedBytes = 4_096, totalBytes = 0),
            )

        assertEquals(ViewerVideoProgressPolicy.Display.Unsized(4_096), display)
    }

    @Test
    fun knownTotalMapsPercentOntoProgressBar() {
        val display =
            ViewerVideoProgressPolicy.display(
                ProtonOriginalDownloadProgress(downloadedBytes = 250, totalBytes = 1_000),
            )

        assertEquals(
            ViewerVideoProgressPolicy.Display.Sized(
                downloadedBytes = 250,
                totalBytes = 1_000,
                percent = 25,
                progress = 250,
            ),
            display,
        )
    }
}
