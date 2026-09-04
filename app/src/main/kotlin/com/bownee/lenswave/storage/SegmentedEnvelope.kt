package com.bownee.lenswave.storage

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Streams a file through AES-GCM one fixed-size segment at a time.
 *
 * Android's default provider implements GCM as a one-shot AEAD: `update` buffers everything and
 * nothing comes out before `doFinal`, so a whole-file cipher stream holds the entire original in
 * the heap and cannot be stopped once started. Here every segment is its own GCM record, so at
 * most one segment of plaintext and one of ciphertext are in memory, a corrupt segment is
 * rejected before any of its plaintext is written, and [decrypt] can stop between segments.
 *
 * Layout, after whatever header the caller writes:
 *
 * ```
 * segmentBytes  int32 big-endian, plaintext bytes per full segment
 * nonce         8 random bytes, fresh per file
 * segment 0     AES-GCM(segmentBytes plaintext) + 16-byte tag
 * ...
 * segment n     AES-GCM(0 until segmentBytes - 1 plaintext) + tag   (always shorter than a full one)
 * ```
 *
 * Segment `i` uses the 96-bit IV `nonce || i` (counter big-endian) and binds `segmentBytes`,
 * `i` and a final flag as associated data. Under one key the nonce is random per file and the
 * counter is unique per segment, so no IV repeats; a moved, dropped or duplicated segment fails
 * its tag, appended bytes change the final segment and fail its tag, and a file cut at a segment
 * boundary is missing the mandatory shorter final segment.
 * A plaintext that is an exact multiple of the segment size ends with an empty final segment.
 */
internal object SegmentedEnvelope {
    const val DEFAULT_SEGMENT_BYTES = 1 shl 20
    const val MAX_SEGMENT_BYTES = 1 shl 24
    const val NONCE_BYTES = 8
    const val TAG_BYTES = 16

    /** Encrypts all of [input] to [output]; returns the plaintext byte count. */
    fun encrypt(
        key: SecretKey,
        input: InputStream,
        output: OutputStream,
        segmentBytes: Int = DEFAULT_SEGMENT_BYTES,
        random: SecureRandom = SecureRandom(),
    ): Long {
        require(segmentBytes in 1..MAX_SEGMENT_BYTES) { "Segment size is out of range" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(segmentBytes).array())
        output.write(nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val plaintext = ByteArray(segmentBytes)
        val ciphertext = ByteArray(segmentBytes + TAG_BYTES)
        var index = 0
        var total = 0L
        while (true) {
            val read = readFully(input, plaintext)
            val final = read < segmentBytes
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, segmentIv(nonce, index)))
            cipher.updateAAD(associatedData(segmentBytes, index, final))
            val produced = cipher.doFinal(plaintext, 0, read, ciphertext, 0)
            output.write(ciphertext, 0, produced)
            total += read
            if (final) return total
            index = nextIndex(index)
        }
    }

    /**
     * Decrypts [input] to [output], writing each segment only after its tag verified; returns the
     * plaintext byte count. [shouldContinue] is asked before every segment; once it returns false
     * the decrypt stops with a [CopyInterruptedException] and only whole verified segments have
     * been written. Any damage to the file surfaces as [IllegalArgumentException] or
     * [javax.crypto.AEADBadTagException].
     */
    fun decrypt(
        key: SecretKey,
        input: InputStream,
        output: OutputStream,
        shouldContinue: () -> Boolean = { true },
    ): Long {
        val header = ByteArray(Int.SIZE_BYTES + NONCE_BYTES)
        require(readFully(input, header) == header.size) { "Encrypted file is truncated" }
        val segmentBytes = ByteBuffer.wrap(header).int
        require(segmentBytes in 1..MAX_SEGMENT_BYTES) { "Encrypted file segment size is invalid" }
        val nonce = header.copyOfRange(Int.SIZE_BYTES, header.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val ciphertext = ByteArray(segmentBytes + TAG_BYTES)
        val plaintext = ByteArray(segmentBytes)
        var index = 0
        var total = 0L
        while (true) {
            if (!shouldContinue()) throw CopyInterruptedException()
            val read = readFully(input, ciphertext)
            val final = read < ciphertext.size
            require(!final || read >= TAG_BYTES) { "Encrypted file is truncated" }
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, segmentIv(nonce, index)))
            cipher.updateAAD(associatedData(segmentBytes, index, final))
            val produced = cipher.doFinal(ciphertext, 0, read, plaintext, 0)
            output.write(plaintext, 0, produced)
            total += produced
            if (final) return total
            index = nextIndex(index)
        }
    }

    /** The 96-bit IV of segment [index]: the file nonce followed by the big-endian counter. */
    fun segmentIv(
        nonce: ByteArray,
        index: Int,
    ): ByteArray {
        require(nonce.size == NONCE_BYTES) { "File nonce has the wrong size" }
        require(index >= 0) { "Segment index is negative" }
        return ByteBuffer
            .allocate(NONCE_BYTES + Int.SIZE_BYTES)
            .put(nonce)
            .putInt(index)
            .array()
    }

    fun associatedData(
        segmentBytes: Int,
        index: Int,
        final: Boolean,
    ): ByteArray =
        ByteBuffer
            .allocate(Int.SIZE_BYTES + Int.SIZE_BYTES + 1)
            .putInt(segmentBytes)
            .putInt(index)
            .put(if (final) 1 else 0)
            .array()

    private fun nextIndex(index: Int): Int {
        check(index < Int.MAX_VALUE) { "File has too many segments" }
        return index + 1
    }

    /** Fills [buffer] unless the stream ends first; returns the bytes read. */
    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = TAG_BYTES * 8
}

/** A [CancellationException] so a coroutine that cancelled the decrypt sees ordinary cancellation. */
internal class CopyInterruptedException : CancellationException("Copy interrupted before completion")
