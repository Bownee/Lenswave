package com.bownee.lenswave.proton

import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProtonOriginalDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val complete: Boolean = false,
) {
    val percent: Int?
        get() = totalBytes
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

    fun bytesWritten(byteCount: Int) = lock.withLock {
        if (byteCount <= 0 || downloadComplete || failure != null) return@withLock
        availableBytes += byteCount
        publish(downloadedBytes = availableBytes)
        changed.signalAll()
    }

    fun updateProgress(downloadedBytes: Long, totalBytes: Long?) = lock.withLock {
        if (downloadComplete || failure != null) return@withLock
        publish(
            downloadedBytes = maxOf(availableBytes, downloadedBytes),
            totalBytes = totalBytes ?: mutableProgress.value.totalBytes,
        )
    }

    fun complete() = lock.withLock {
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

    fun fail(error: Throwable) = lock.withLock {
        if (downloadComplete) return@withLock
        failure = error
        changed.signalAll()
    }

    @Throws(IOException::class)
    fun awaitReadable(position: Long): ProtonOriginalReadState = lock.withLock {
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

    private fun publish(
        downloadedBytes: Long,
        totalBytes: Long? = mutableProgress.value.totalBytes,
        complete: Boolean = false,
    ) {
        val validTotal = totalBytes?.takeIf { total -> total > 0L }
        mutableProgress.value = ProtonOriginalDownloadProgress(
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = validTotal,
            complete = complete,
        )
    }
}

data class ProtonOriginalReadState(
    val availableBytes: Long,
    val complete: Boolean,
)
