package com.bownee.lenswave.viewer

import com.bownee.lenswave.proton.ProtonOriginalDownloadProgress

/** Decides how, and whether, a video download's progress is presented over the player. */
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

    /**
     * Whether the progress panel belongs on screen. Before the first frame it always does. Once
     * the video plays it comes back only while the player is buffering against a download that
     * is still in flight: a seek past the downloaded bytes parks the player on them, and without
     * the panel that wait is a silent freeze. A complete download leaves stalls to the player's
     * own buffering indicator.
     */
    fun panelVisible(
        mediaReady: Boolean,
        buffering: Boolean,
        streamComplete: Boolean,
    ): Boolean = !mediaReady || (buffering && !streamComplete)

    /** Whether the progress collector still has anything to report: nothing changes once every byte is on disk. */
    fun keepObserving(streamComplete: Boolean): Boolean = !streamComplete
}
