package com.bownee.lenswave.proton

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ProtonOriginalDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val complete: Boolean = false,
) {
    val percent: Int?
        get() =
            totalBytes
                ?.takeIf { total -> total > 0L }
                ?.let { total -> ((downloadedBytes.coerceIn(0L, total) * 100L) / total).toInt() }
}

/**
 * A file that can be read while bytes are still being appended to it: by the Proton SDK during
 * a download, or by the decrypt of a cached original.
 *
 * [file] is where the bytes are right now. A decrypt writes into a temporary file and renames it
 * once the whole original verified, so [complete] may move [file]; a reader that finds its path
 * gone waits for [awaitCompletion] and opens [file] again.
 */
class ProtonOriginalStream(
    file: File,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val mutableProgress = MutableStateFlow(ProtonOriginalDownloadProgress())
    private var availableBytes = file.length()
    private var failure: Throwable? = null
    private var downloadComplete = false

    @Volatile
    var file: File = file
        private set

    val progress: StateFlow<ProtonOriginalDownloadProgress> = mutableProgress.asStateFlow()

    fun bytesWritten(byteCount: Int) =
        lock.withLock {
            if (byteCount <= 0 || downloadComplete || failure != null) return@withLock
            availableBytes += byteCount
            publish(downloadedBytes = availableBytes)
            changed.signalAll()
        }

    /** The readable prefix is now [totalBytes] long; a total behind what is known is ignored. */
    fun availableBytes(totalBytes: Long) =
        lock.withLock {
            if (totalBytes <= availableBytes || downloadComplete || failure != null) return@withLock
            availableBytes = totalBytes
            publish(downloadedBytes = availableBytes)
            changed.signalAll()
        }

    fun updateProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
    ) = lock.withLock {
        if (downloadComplete || failure != null) return@withLock
        publish(
            downloadedBytes = maxOf(availableBytes, downloadedBytes),
            totalBytes = totalBytes ?: mutableProgress.value.totalBytes,
        )
    }

    /** Every byte is on disk, in [committed]: the same file, or the name the growing file was renamed to. */
    fun complete(committed: File = file) =
        lock.withLock {
            if (failure != null) return@withLock
            file = committed
            availableBytes = maxOf(availableBytes, committed.length())
            downloadComplete = true
            publish(
                downloadedBytes = availableBytes,
                totalBytes = mutableProgress.value.totalBytes ?: availableBytes,
                complete = true,
            )
            changed.signalAll()
        }

    fun fail(error: Throwable) =
        lock.withLock {
            if (downloadComplete) return@withLock
            failure = error
            changed.signalAll()
        }

    @Throws(IOException::class)
    fun awaitReadable(position: Long): ProtonOriginalReadState =
        lock.withLock {
            try {
                while (availableBytes <= position && !downloadComplete && failure == null) {
                    changed.await()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Proton media", error)
            }
            failure?.let { error -> throw IOException("Proton media download failed", error) }
            ProtonOriginalReadState(availableBytes, downloadComplete)
        }

    /** Waits until every byte is on disk, then returns the final [file]. */
    @Throws(IOException::class)
    fun awaitCompletion(): File {
        awaitReadable(Long.MAX_VALUE)
        return file
    }

    /**
     * Emits only when the change is one a reader would notice: a StateFlow value per channel
     * write would wake the collector for every few kilobytes of a multi-megabyte transfer.
     * The condition variable for readers is signalled by the callers regardless.
     */
    private fun publish(
        downloadedBytes: Long,
        totalBytes: Long? = mutableProgress.value.totalBytes,
        complete: Boolean = false,
    ) {
        val validTotal = totalBytes?.takeIf { total -> total > 0L }
        val next =
            ProtonOriginalDownloadProgress(
                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                totalBytes = validTotal,
                complete = complete,
            )
        if (ProtonOriginalProgressPolicy.shouldPublish(mutableProgress.value, next)) mutableProgress.value = next
    }
}

data class ProtonOriginalReadState(
    val availableBytes: Long,
    val complete: Boolean,
)

/** Which progress updates are worth publishing to the viewer. */
internal object ProtonOriginalProgressPolicy {
    /** With no total to compute a percentage from, publish every this many bytes. */
    const val MIN_BYTE_DELTA = 256L * 1_024L

    fun shouldPublish(
        previous: ProtonOriginalDownloadProgress,
        next: ProtonOriginalDownloadProgress,
    ): Boolean =
        when {
            next.complete != previous.complete -> true
            next.totalBytes != previous.totalBytes -> true
            next.downloadedBytes < previous.downloadedBytes -> true
            next.percent != null -> next.percent != previous.percent
            else -> next.downloadedBytes - previous.downloadedBytes >= MIN_BYTE_DELTA
        }
}
