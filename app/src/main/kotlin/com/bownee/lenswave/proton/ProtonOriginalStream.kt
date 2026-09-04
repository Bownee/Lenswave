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

/** A file that can be read while the Proton SDK is still appending decrypted bytes to it. */
class ProtonOriginalStream(
    val file: File,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val mutableProgress = MutableStateFlow(ProtonOriginalDownloadProgress())
    private var availableBytes = file.length()
    private var failure: Throwable? = null
    private var downloadComplete = false

    val progress: StateFlow<ProtonOriginalDownloadProgress> = mutableProgress.asStateFlow()

    fun bytesWritten(byteCount: Int) =
        lock.withLock {
            if (byteCount <= 0 || downloadComplete || failure != null) return@withLock
            availableBytes += byteCount
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

    fun complete() =
        lock.withLock {
            if (failure != null) return@withLock
            availableBytes = maxOf(availableBytes, file.length())
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
