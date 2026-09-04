package com.bownee.lenswave.storage

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Streams [input] to [output] in chunks, asking [shouldContinue] before each one.
 *
 * Decrypting a full-size original takes long enough that a viewer swipe can make it pointless
 * halfway through; a caller passes the liveness of its coroutine and the copy stops at the next
 * chunk boundary instead of finishing work nobody will read.
 */
internal object InterruptibleCopy {
    const val DEFAULT_CHUNK_BYTES = 64 * 1024

    /** Bytes copied; throws [CopyInterruptedException] once [shouldContinue] returns false. */
    fun copy(
        input: InputStream,
        output: OutputStream,
        shouldContinue: () -> Boolean,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    ): Long {
        require(chunkBytes > 0) { "Chunk size must be positive" }
        val buffer = ByteArray(chunkBytes)
        var copied = 0L
        while (true) {
            if (!shouldContinue()) throw CopyInterruptedException()
            val read = input.read(buffer)
            if (read < 0) return copied
            output.write(buffer, 0, read)
            copied += read
        }
    }
}

/** A [CancellationException] so a coroutine that cancelled the copy sees ordinary cancellation. */
internal class CopyInterruptedException : CancellationException("Copy interrupted before completion")
