package com.bownee.lenswave.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class InterruptibleCopyTest {
    @Test
    fun copiesEverythingWhileAllowedToContinue() {
        val source = ByteArray(10_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        val copied = InterruptibleCopy.copy(ByteArrayInputStream(source), output, { true }, chunkBytes = 1_024)

        assertEquals(source.size.toLong(), copied)
        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun stopsAtTheNextChunkBoundaryOnceAskedToStop() {
        val source = ByteArray(10_000)
        val output = ByteArrayOutputStream()
        var checks = 0

        assertThrows(CopyInterruptedException::class.java) {
            InterruptibleCopy.copy(ByteArrayInputStream(source), output, { ++checks <= 3 }, chunkBytes = 1_024)
        }

        // Three chunks were allowed through; the fourth check refused before its chunk was read.
        assertEquals(3 * 1_024, output.size())
        assertEquals(4, checks)
    }

    @Test
    fun asksBeforeTheFirstChunkAndAfterTheLastOne() {
        val source = ByteArray(2_048)
        var checks = 0

        InterruptibleCopy.copy(ByteArrayInputStream(source), ByteArrayOutputStream(), {
            checks++
            true
        }, chunkBytes = 1_024)

        // Two data chunks plus the final read that reports end of stream.
        assertEquals(3, checks)
    }

    @Test
    fun refusingBeforeAnythingIsReadCopiesNothing() {
        val output = ByteArrayOutputStream()

        assertThrows(CopyInterruptedException::class.java) {
            InterruptibleCopy.copy(ByteArrayInputStream(ByteArray(64)), output, { false })
        }

        assertEquals(0, output.size())
    }

    @Test
    fun rejectsANonPositiveChunkSize() {
        assertThrows(IllegalArgumentException::class.java) {
            InterruptibleCopy.copy(
                ByteArrayInputStream(ByteArray(1)),
                ByteArrayOutputStream(),
                { true },
                chunkBytes = 0,
            )
        }
    }
}
