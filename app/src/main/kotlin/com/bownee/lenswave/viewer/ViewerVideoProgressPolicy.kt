package com.bownee.lenswave.viewer

import com.bownee.lenswave.proton.ProtonOriginalDownloadProgress

/** Decides how a video download's progress is presented while the first frame is still pending. */
internal object ViewerVideoProgressPolicy {
    const val PROGRESS_MAX = 1_000

    sealed interface Display {
        /** All bytes are on disk; the player is decoding the first frame. */
        data object Preparing : Display

        /** Proton did not report a size, so only the downloaded amount can be shown. */
        data class Unsized(
            val downloadedBytes: Long,
        ) : Display

        data class Sized(
            val downloadedBytes: Long,
            val totalBytes: Long,
            val percent: Int,
            /** Position on a bar whose maximum is [PROGRESS_MAX]. */
            val progress: Int,
        ) : Display
    }

    fun display(downloadProgress: ProtonOriginalDownloadProgress): Display {
        if (downloadProgress.complete) return Display.Preparing
        val totalBytes = downloadProgress.totalBytes
        val percent = downloadProgress.percent
        if (percent == null || totalBytes == null) return Display.Unsized(downloadProgress.downloadedBytes)
        return Display.Sized(
            downloadedBytes = downloadProgress.downloadedBytes,
            totalBytes = totalBytes,
            percent = percent,
            progress = percent * PROGRESS_MAX / 100,
        )
    }
}
