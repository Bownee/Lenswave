package com.bownee.lenswave.viewer

import com.bownee.lenswave.proton.ProtonOriginalDownloadProgress

/** Decides how, and whether, a video download's progress is presented over the player. */
internal object ViewerVideoProgressPolicy {
    const val PROGRESS_MAX = 1_000

    sealed interface Display {
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
     * Whether the progress panel belongs on screen: only while the download is in flight. Before
     * the first frame it shows the download; once the video plays it comes back only while the
     * player is buffering, or a reader of the file is waiting for bytes, against a download
     * still in flight: a seek past the downloaded bytes parks the player on them, and without
     * the panel that wait is a silent freeze. The reader's wait is reported by the stream itself
     * and can precede the player's buffering state. A complete download shows nothing: the
     * player's own decoding of the first frame happens behind the thumbnail without a spinner,
     * and stalls after that are left to the player's own buffering indicator.
     */
    fun panelVisible(
        mediaReady: Boolean,
        buffering: Boolean,
        waitingForBytes: Boolean,
        streamComplete: Boolean,
    ): Boolean = !streamComplete && (!mediaReady || buffering || waitingForBytes)

    /** Whether the progress collector still has anything to report: nothing changes once every byte is on disk. */
    fun keepObserving(streamComplete: Boolean): Boolean = !streamComplete
}
