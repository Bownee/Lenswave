package com.bownee.lenswave.proton

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import kotlin.math.min

/** Media3 source that waits for a foreground Proton download instead of treating a growing file as EOF. */
@OptIn(UnstableApi::class)
internal class ProtonProgressiveDataSource(
    private val stream: ProtonOriginalStream,
) : BaseDataSource(false) {
    private var input: RandomAccessFile? = null
    private var opened = false
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val state = stream.awaitReadable(dataSpec.position)
        val remaining =
            ProtonProgressiveReadPolicy.bytesRemaining(
                position = dataSpec.position,
                requestedLength = dataSpec.length,
                availableBytes = state.availableBytes,
                complete = state.complete,
            )
        if (remaining == null) {
            // Worth a line in the log: this is what a truncated or wrongly sized download looks like.
            // The state is a fixed token; the offsets would only push the line past its size cap.
            LenswaveDiagnostics.reportState(LenswaveOperation.VIDEO_PLAYBACK, STATE_OPEN_BEYOND_END, 1, 1)
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        input = openInput().apply { seek(dataSpec.position) }
        readPosition = dataSpec.position
        bytesRemaining = remaining
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = checkNotNull(input) { "Data source is not open" }
        while (true) {
            val state = stream.awaitReadable(readPosition)
            val available = state.availableBytes - readPosition
            if (available <= 0L && state.complete) return C.RESULT_END_OF_INPUT
            if (available <= 0L) {
                stream.awaitChange(READ_RETRY_WAIT_MILLIS)
                continue
            }
            val requested =
                min(length.toLong(), available)
                    .let { availableLength ->
                        if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                            availableLength
                        } else {
                            min(availableLength, bytesRemaining)
                        }
                    }.toInt()
            if (requested == 0) return C.RESULT_END_OF_INPUT
            val read = source.read(buffer, offset, requested)
            if (read < 0) {
                if (state.complete) return C.RESULT_END_OF_INPUT
                // The stream reported bytes the file does not show yet; a bounded wait on the
                // stream's condition instead of a spin until the writer catches up.
                stream.awaitChange(READ_RETRY_WAIT_MILLIS)
                continue
            }
            readPosition += read
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
            bytesTransferred(read)
            return read
        }
    }

    /**
     * A decrypt in progress writes into a temporary file that is renamed once it has verified.
     * An open descriptor survives the rename, but an open that lands in between finds the
     * temporary gone; the stream is about to complete then, so the open waits for it and takes
     * the final file.
     */
    private fun openInput(): RandomAccessFile =
        try {
            RandomAccessFile(stream.file, "r")
        } catch (_: FileNotFoundException) {
            RandomAccessFile(stream.awaitCompletion(), "r")
        }

    override fun getUri(): Uri = Uri.fromFile(stream.file)

    override fun close() {
        closeInput()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeInput() {
        try {
            input?.close()
        } finally {
            input = null
        }
    }

    class Factory(
        private val stream: ProtonOriginalStream,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ProtonProgressiveDataSource(stream)
    }

    private companion object {
        const val STATE_OPEN_BEYOND_END = "open-beyond-end"
        const val READ_RETRY_WAIT_MILLIS = 20L
    }
}
