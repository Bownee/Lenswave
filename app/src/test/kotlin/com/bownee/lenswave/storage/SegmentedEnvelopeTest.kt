package com.bownee.lenswave.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class SegmentedEnvelopeTest {
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")
    private val otherKey: SecretKey = SecretKeySpec(ByteArray(32) { (it * 11 + 5).toByte() }, "AES")

    @Test
    fun roundTripsAcrossSegmentBoundaries() {
        val plaintext = pattern(SEGMENT * 2 + SEGMENT / 2)

        val encrypted = encrypt(plaintext)

        assertEquals(HEADER + 3 * TAG + plaintext.size, encrypted.size)
        assertArrayEquals(plaintext, decrypt(encrypted))
    }

    @Test
    fun exactMultipleEndsWithAnEmptyFinalSegment() {
        val plaintext = pattern(SEGMENT * 3)

        val encrypted = encrypt(plaintext)

        // Three full segments plus an empty final one that only carries a tag.
        assertEquals(HEADER + 4 * TAG + plaintext.size, encrypted.size)
        assertArrayEquals(plaintext, decrypt(encrypted))
    }

    @Test
    fun roundTripsAnEmptyFile() {
        val encrypted = encrypt(ByteArray(0))

        assertEquals(HEADER + TAG, encrypted.size)
        assertArrayEquals(ByteArray(0), decrypt(encrypted))
    }

    @Test
    fun roundTripsAFileSmallerThanOneSegment() {
        val plaintext = pattern(SEGMENT - 1)

        assertArrayEquals(plaintext, decrypt(encrypt(plaintext)))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintextAndDiffersPerFile() {
        val plaintext = pattern(SEGMENT + 10)

        val first = encrypt(plaintext)
        val second = encrypt(plaintext)

        assertFalse(first.contentEquals(second))
        assertFalse(first.containsSubsequence(plaintext.copyOfRange(0, 64)))
    }

    @Test
    fun rejectsAFileTruncatedInsideASegment() {
        val encrypted = encrypt(pattern(SEGMENT + 10))

        assertThrows(AEADBadTagException::class.java) { decrypt(encrypted.copyOf(encrypted.size - 1)) }
    }

    @Test
    fun rejectsAFileCutAtASegmentBoundary() {
        val encrypted = encrypt(pattern(SEGMENT * 2 + 10))
        val output = ByteArrayOutputStream()

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SegmentedEnvelope.decrypt(
                    key,
                    ByteArrayInputStream(encrypted.copyOf(HEADER + 2 * (SEGMENT + TAG))),
                    output,
                )
            }

        assertEquals("Encrypted file is truncated", error.message)
        // The two whole segments before the cut verified and were written; the loss is detected after.
        assertEquals(SEGMENT * 2, output.size())
    }

    @Test
    fun rejectsATruncatedHeader() {
        val encrypted = encrypt(pattern(10))

        assertThrows(IllegalArgumentException::class.java) { decrypt(encrypted.copyOf(HEADER - 1)) }
    }

    @Test
    fun rejectsReorderedSegments() {
        val encrypted = encrypt(pattern(SEGMENT * 2 + 10))
        val swapped = encrypted.copyOf()
        val first = HEADER
        val second = HEADER + SEGMENT + TAG
        System.arraycopy(encrypted, second, swapped, first, SEGMENT + TAG)
        System.arraycopy(encrypted, first, swapped, second, SEGMENT + TAG)

        assertThrows(AEADBadTagException::class.java) { decrypt(swapped) }
    }

    @Test
    fun rejectsADuplicatedSegment() {
        val encrypted = encrypt(pattern(SEGMENT * 2 + 10))
        val duplicated = encrypted.copyOf()
        System.arraycopy(encrypted, HEADER, duplicated, HEADER + SEGMENT + TAG, SEGMENT + TAG)

        assertThrows(AEADBadTagException::class.java) { decrypt(duplicated) }
    }

    @Test
    fun rejectsAFinalSegmentPresentedAsAFullOne() {
        // Drop the middle segment so the final (shorter) segment is read at index 1.
        val encrypted = encrypt(pattern(SEGMENT * 2 + 10))
        val dropped =
            encrypted.copyOfRange(0, HEADER + SEGMENT + TAG) +
                encrypted.copyOfRange(HEADER + 2 * (SEGMENT + TAG), encrypted.size)

        assertThrows(AEADBadTagException::class.java) { decrypt(dropped) }
    }

    @Test
    fun rejectsAFlippedByteInASegment() {
        val encrypted = encrypt(pattern(SEGMENT + 10))
        val damaged = encrypted.copyOf()
        damaged[HEADER + 5] = (damaged[HEADER + 5].toInt() xor 0x40).toByte()

        assertThrows(AEADBadTagException::class.java) { decrypt(damaged) }
    }

    @Test
    fun rejectsAFlippedByteInTheNonce() {
        val encrypted = encrypt(pattern(SEGMENT + 10))
        val damaged = encrypted.copyOf()
        damaged[Int.SIZE_BYTES + 2] = (damaged[Int.SIZE_BYTES + 2].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) { decrypt(damaged) }
    }

    @Test
    fun rejectsATamperedSegmentSize() {
        val encrypted = encrypt(pattern(SEGMENT + 10))
        val damaged = encrypted.copyOf()
        // Halve the declared segment size; the segments are re-sliced and the first tag fails.
        damaged[3] = (SEGMENT / 2).toByte()
        damaged[2] = (SEGMENT / 2 shr 8).toByte()

        assertThrows(AEADBadTagException::class.java) { decrypt(damaged) }
    }

    @Test
    fun rejectsAnInvalidSegmentSize() {
        val encrypted = encrypt(pattern(10))
        val zero = encrypted.copyOf().also { it.fill(0, 0, Int.SIZE_BYTES) }
        val huge = encrypted.copyOf().also { it[0] = 0x7f }

        assertThrows(IllegalArgumentException::class.java) { decrypt(zero) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(huge) }
    }

    @Test
    fun rejectsTrailingData() {
        val encrypted = encrypt(pattern(SEGMENT + 10))

        // Appended bytes become part of the final segment, whose tag then no longer verifies.
        assertThrows(AEADBadTagException::class.java) { decrypt(encrypted + byteArrayOf(0)) }
        assertThrows(AEADBadTagException::class.java) { decrypt(encrypted + ByteArray(SEGMENT + TAG)) }
    }

    @Test
    fun rejectsTheWrongKey() {
        val encrypted = encrypt(pattern(10))

        assertThrows(AEADBadTagException::class.java) {
            SegmentedEnvelope.decrypt(otherKey, ByteArrayInputStream(encrypted), ByteArrayOutputStream())
        }
    }

    @Test
    fun stopsBetweenSegmentsOnceAskedTo() {
        val plaintext = pattern(SEGMENT * 3 + 10)
        val encrypted = encrypt(plaintext)
        val output = ByteArrayOutputStream()
        var checks = 0

        assertThrows(CopyInterruptedException::class.java) {
            SegmentedEnvelope.decrypt(key, ByteArrayInputStream(encrypted), output) { ++checks <= 2 }
        }

        // Two segments were allowed through, whole and verified; the third check refused.
        assertEquals(3, checks)
        assertArrayEquals(plaintext.copyOf(SEGMENT * 2), output.toByteArray())
    }

    @Test
    fun refusingBeforeTheFirstSegmentWritesNothing() {
        val encrypted = encrypt(pattern(SEGMENT + 10))
        val output = ByteArrayOutputStream()

        assertThrows(CopyInterruptedException::class.java) {
            SegmentedEnvelope.decrypt(key, ByteArrayInputStream(encrypted), output) { false }
        }

        assertEquals(0, output.size())
    }

    @Test
    fun asksOncePerSegmentWhenAllowedThrough() {
        val encrypted = encrypt(pattern(SEGMENT * 2))
        var checks = 0

        SegmentedEnvelope.decrypt(key, ByteArrayInputStream(encrypted), ByteArrayOutputStream()) {
            checks++
            true
        }

        // Two full segments plus the empty final one.
        assertEquals(3, checks)
    }

    @Test
    fun reportsThePlaintextTotalAfterEveryVerifiedSegment() {
        val encrypted = encrypt(pattern(SEGMENT * 2 + 10))
        val totals = mutableListOf<Long>()
        val output = ByteArrayOutputStream()

        SegmentedEnvelope.decrypt(key, ByteArrayInputStream(encrypted), output, onBytesWritten = totals::add)

        assertEquals(listOf(SEGMENT.toLong(), 2L * SEGMENT, 2L * SEGMENT + 10), totals)
        // Each total was reported only once its bytes had been written.
        assertEquals(totals.last(), output.size().toLong())
    }

    @Test
    fun segmentIvIsTheNonceFollowedByTheCounter() {
        val nonce = ByteArray(SegmentedEnvelope.NONCE_BYTES) { (it + 1).toByte() }

        val first = SegmentedEnvelope.segmentIv(nonce, 0)
        val second = SegmentedEnvelope.segmentIv(nonce, 0x01020304)

        assertEquals(12, first.size)
        assertArrayEquals(nonce, first.copyOf(SegmentedEnvelope.NONCE_BYTES))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), first.copyOfRange(SegmentedEnvelope.NONCE_BYTES, 12))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), second.copyOfRange(SegmentedEnvelope.NONCE_BYTES, 12))
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun segmentIvRejectsABadNonceOrIndex() {
        assertThrows(IllegalArgumentException::class.java) { SegmentedEnvelope.segmentIv(ByteArray(7), 0) }
        assertThrows(IllegalArgumentException::class.java) {
            SegmentedEnvelope.segmentIv(ByteArray(SegmentedEnvelope.NONCE_BYTES), -1)
        }
    }

    @Test
    fun associatedDataBindsSizeIndexAndFinality() {
        val body = SegmentedEnvelope.associatedData(SEGMENT, 2, final = false)
        val last = SegmentedEnvelope.associatedData(SEGMENT, 2, final = true)

        assertEquals(9, body.size)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 2, 0), body)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 2, 1), last)
    }

    @Test
    fun encryptRejectsAnOutOfRangeSegmentSize() {
        assertThrows(IllegalArgumentException::class.java) {
            SegmentedEnvelope.encrypt(key, ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SegmentedEnvelope.encrypt(
                key,
                ByteArrayInputStream(ByteArray(1)),
                ByteArrayOutputStream(),
                SegmentedEnvelope.MAX_SEGMENT_BYTES + 1,
            )
        }
    }

    @Test
    fun defaultSegmentIsOneMebibyte() {
        val plaintext = pattern(SegmentedEnvelope.DEFAULT_SEGMENT_BYTES + 1)
        val output = ByteArrayOutputStream()

        val written = SegmentedEnvelope.encrypt(key, ByteArrayInputStream(plaintext), output)

        assertEquals(plaintext.size.toLong(), written)
        assertEquals(HEADER + 2 * TAG + plaintext.size, output.size())
        assertArrayEquals(plaintext, decrypt(output.toByteArray()))
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val written = SegmentedEnvelope.encrypt(key, ByteArrayInputStream(plaintext), output, SEGMENT, SecureRandom())
        assertEquals(plaintext.size.toLong(), written)
        return output.toByteArray()
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val read = SegmentedEnvelope.decrypt(key, ByteArrayInputStream(encrypted), output)
        assertEquals(output.size().toLong(), read)
        return output.toByteArray()
    }

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }

    private fun ByteArray.containsSubsequence(value: ByteArray): Boolean =
        indices.any { start ->
            start + value.size <= size && value.indices.all { offset -> this[start + offset] == value[offset] }
        }

    private companion object {
        const val SEGMENT = 1024
        const val TAG = SegmentedEnvelope.TAG_BYTES
        const val HEADER = Int.SIZE_BYTES + SegmentedEnvelope.NONCE_BYTES
    }
}
