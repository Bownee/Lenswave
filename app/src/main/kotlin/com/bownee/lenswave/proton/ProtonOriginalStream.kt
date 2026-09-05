package com.bownee.lenswave.proton

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ProtonOriginalDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val complete: Boolean = false,
) {
    val percent: Int?
        get() = ProtonOriginalProgressPolicy.percent(downloadedBytes, totalBytes)
}

/**
 * A file that can be read while bytes are still being appended to it: by the Proton SDK during
 * a download, or by the decrypt of a cached original.
 *
 * [file] is where the bytes are right now. A decrypt writes into a temporary file and renames it
 * once the whole original verified, so [complete] may move [file]; a reader that finds its path
 * gone waits for [awaitCompletion] and opens [file] again.
 *
 * A reader announces itself through [readerOpened] and [readerClosed]; while one holds the file
 * it is reported to [readers], and its age restarts on every open and close, so the plaintext
 * TTL counts from the last use rather than from the decrypt.
 */
class ProtonOriginalStream(
    file: File,
    private val readers: ProtonOriginalReaders = ProtonOriginalReaders.None,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val mutableProgress = MutableStateFlow(ProtonOriginalDownloadProgress())
    private var availableBytes = file.length()
    private var failure: Throwable? = null
    private var downloadComplete = false
    private var openReaders = 0

    private var blockedReaders = 0
    private val mutableWaitingForBytes = MutableStateFlow(false)

    @Volatile
    var file: File = file
        private set

    val progress: StateFlow<ProtonOriginalDownloadProgress> = mutableProgress.asStateFlow()

    /**
     * True while a reader is blocked at a position the file has not reached yet: the player
     * seeked past the downloaded prefix, or ran out of bytes mid-playback. The viewer shows the
     * download progress again while this is set and hides it once the reader is served.
     */
    val waitingForBytes: StateFlow<Boolean> = mutableWaitingForBytes.asStateFlow()

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

    /**
     * A reader is about to open [file]; returns it. The copy is reported as in use until the
     * matching [readerClosed], across a rename by [complete].
     */
    fun readerOpened(): File =
        lock.withLock {
            if (openReaders++ == 0) readers.opened(file)
            touch(file)
            file
        }

    fun readerClosed() =
        lock.withLock {
            if (openReaders == 0) return@withLock
            if (--openReaders == 0) {
                readers.closed(file)
                touch(file)
            }
        }

    /** True once [complete] ran: every byte is in [file], and a path that is gone will not come back. */
    val isComplete: Boolean
        get() = lock.withLock { downloadComplete }

    /** Every byte is on disk, in [committed]: the same file, or the name the growing file was renamed to. */
    fun complete(committed: File = file) =
        lock.withLock {
            if (failure != null) return@withLock
            if (openReaders > 0 && committed != file) {
                readers.closed(file)
                readers.opened(committed)
            }
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
            if (availableBytes <= position && !downloadComplete && failure == null) {
                blockedReaders++
                mutableWaitingForBytes.value = true
                try {
                    while (availableBytes <= position && !downloadComplete && failure == null) {
                        changed.await()
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for Proton media", error)
                } finally {
                    if (--blockedReaders == 0) mutableWaitingForBytes.value = false
                }
            }
            failure?.let { error -> throw IOException("Proton media download failed", error) }
            ProtonOriginalReadState(availableBytes, downloadComplete)
        }

    /**
     * Waits until the stream changes or [timeoutMillis] pass, for a reader whose file lags what
     * the stream reports: a bounded pause instead of a spin.
     */
    @Throws(IOException::class)
    fun awaitChange(timeoutMillis: Long) =
        lock.withLock {
            if (downloadComplete || failure != null) return@withLock
            try {
                changed.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Proton media", error)
            }
        }

    /** Waits until every byte is on disk, then returns the final [file]. */
    @Throws(IOException::class)
    fun awaitCompletion(): File {
        awaitReadable(Long.MAX_VALUE)
        return file
    }

    /** Best effort: the age of a copy nobody can stamp simply keeps counting from the decrypt. */
    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
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
        val validDownloaded = downloadedBytes.coerceAtLeast(0L)
        // Decided on primitives first: a value object per channel write, under the lock, was
        // most of the allocation of a large transfer.
        val previous = mutableProgress.value
        if (ProtonOriginalProgressPolicy.shouldPublish(previous, validDownloaded, validTotal, complete)) {
            mutableProgress.value = ProtonOriginalDownloadProgress(validDownloaded, validTotal, complete)
        }
    }
}

/**
 * The plaintext copy of a complete stream is gone from disk: swept, or removed with its node.
 * A [FileNotFoundException] so Media3 fails the load at once instead of retrying a file that
 * will not come back; the viewer runs the download again once, which decrypts a fresh copy.
 */
class ProtonOriginalCopyMissingException(
    cause: Throwable,
) : FileNotFoundException("The plaintext copy of the original is gone") {
    init {
        initCause(cause)
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

    /** Whole percent of [downloadedBytes] in [totalBytes]; null without a usable total. */
    fun percent(
        downloadedBytes: Long,
        totalBytes: Long?,
    ): Int? =
        totalBytes
            ?.takeIf { total -> total > 0L }
            ?.let { total -> ((downloadedBytes.coerceIn(0L, total) * 100L) / total).toInt() }

    /** Whether a change to ([downloadedBytes], [totalBytes], [complete]) is worth publishing over [previous]. */
    fun shouldPublish(
        previous: ProtonOriginalDownloadProgress,
        downloadedBytes: Long,
        totalBytes: Long?,
        complete: Boolean,
    ): Boolean =
        shouldPublish(
            previousDownloadedBytes = previous.downloadedBytes,
            previousTotalBytes = previous.totalBytes,
            previousComplete = previous.complete,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            complete = complete,
        )

    /** The rule on primitives, so a caller allocates a progress value only for the updates it publishes. */
    fun shouldPublish(
        previousDownloadedBytes: Long,
        previousTotalBytes: Long?,
        previousComplete: Boolean,
        downloadedBytes: Long,
        totalBytes: Long?,
        complete: Boolean,
    ): Boolean =
        when {
            complete != previousComplete -> {
                true
            }

            totalBytes != previousTotalBytes -> {
                true
            }

            downloadedBytes < previousDownloadedBytes -> {
                true
            }

            else -> {
                val percent = percent(downloadedBytes, totalBytes)
                if (percent != null) {
                    percent != percent(previousDownloadedBytes, previousTotalBytes)
                } else {
                    downloadedBytes - previousDownloadedBytes >= MIN_BYTE_DELTA
                }
            }
        }
}
