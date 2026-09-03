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
import com.bownee.lenswave.proton.ProtonOriginalStream
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
        if (state.complete && dataSpec.position > state.availableBytes) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        input = RandomAccessFile(stream.file, "r").apply { seek(dataSpec.position) }
        readPosition = dataSpec.position
        // The SDK's progress total describes the encrypted transfer, not the decrypted file, so
        // it must not bound the reads: a too-small total would end the stream early and fail the
        // first playback. Only the finished file's real length is trusted.
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            state.complete -> state.availableBytes - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        if (bytesRemaining < 0L) {
            closeInput()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = checkNotNull(input) { "Data source is not open" }
        while (true) {
            val state = stream.awaitReadable(readPosition)
            val available = state.availableBytes - readPosition
            if (available <= 0L && state.complete) return C.RESULT_END_OF_INPUT
            if (available <= 0L) continue
            val requested = min(length.toLong(), available).let { availableLength ->
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) availableLength
                else min(availableLength, bytesRemaining)
            }.toInt()
            if (requested == 0) return C.RESULT_END_OF_INPUT
            val read = source.read(buffer, offset, requested)
            if (read < 0) {
                if (state.complete) return C.RESULT_END_OF_INPUT
                continue
            }
            readPosition += read
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
            bytesTransferred(read)
            return read
        }
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
}
