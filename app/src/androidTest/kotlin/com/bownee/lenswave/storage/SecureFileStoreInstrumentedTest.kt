package com.bownee.lenswave.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class SecureFileStoreInstrumentedTest {
    @Test
    fun payloadIsAuthenticatedEncryptedAndDestroyedWithItsKey() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureFileStore(context)
        val scope = "instrumentation-${UUID.randomUUID()}"
        val target = File(context.cacheDir, "$scope.bin")
        val plaintext = "private Proton token and photo metadata".toByteArray()

        try {
            store.write(scope, target, plaintext, "test write failed")
            val ciphertext = target.readBytes()
            assertFalse(ciphertext.containsSubsequence(plaintext))
            assertArrayEquals(plaintext, store.read(scope, target))

            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            target.writeBytes(ciphertext)
            assertTrue(runCatching { store.read(scope, target) }.isFailure)

            store.write(scope, target, plaintext, "test rewrite failed")
            store.deleteKey(scope)
            assertTrue(runCatching { store.read(scope, target) }.isFailure)
        } finally {
            target.delete()
            store.deleteKey(scope)
        }
    }

    @Test
    fun wholeFileIsSegmentedAuthenticatedAndStoppable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureFileStore(context)
        val scope = "instrumentation-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, scope).apply { mkdirs() }
        val plaintext = File(directory, "original")
        val encrypted = File(directory, "encrypted")
        val decrypted = File(directory, "decrypted")
        // Two full segments plus a partial one, so the file grows by exactly three tags.
        val bytes = ByteArray(2 * SegmentedEnvelope.DEFAULT_SEGMENT_BYTES + 12_345) { (it * 13).toByte() }
        plaintext.writeBytes(bytes)

        try {
            store.encryptFile(scope, plaintext, encrypted, "test encrypt failed")
            val header = 4 + 1 + Int.SIZE_BYTES + SegmentedEnvelope.NONCE_BYTES
            assertEquals(header + 3 * SegmentedEnvelope.TAG_BYTES + bytes.size.toLong(), encrypted.length())
            assertFalse(encrypted.readBytes().containsSubsequence(bytes.copyOf(64)))

            store.decryptFile(scope, encrypted, decrypted)
            assertArrayEquals(bytes, decrypted.readBytes())

            var checks = 0
            decrypted.delete()
            assertTrue(
                runCatching { store.decryptFile(scope, encrypted, decrypted) { ++checks <= 1 } }
                    .exceptionOrNull() is CancellationException,
            )
            assertFalse(decrypted.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })

            val damaged = encrypted.readBytes()
            damaged[header + 100] = (damaged[header + 100].toInt() xor 1).toByte()
            encrypted.writeBytes(damaged)
            assertTrue(runCatching { store.decryptFile(scope, encrypted, decrypted) }.isFailure)
            assertFalse(decrypted.exists())
        } finally {
            directory.deleteRecursively()
            store.deleteKey(scope)
        }
    }

    @Test
    fun legacyWholeFileRecordIsVerifiedBeforeAnyPlaintextIsCommitted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureFileStore(context)
        val scope = "instrumentation-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, scope).apply { mkdirs() }
        val legacy = File(directory, "legacy")
        val decrypted = File(directory, "decrypted")
        val bytes = ByteArray(200_000) { (it * 5).toByte() }

        try {
            // A small payload is one whole-file GCM record, the layout of a version 2 original.
            store.write(scope, legacy, bytes, "test write failed")
            store.decryptFile(scope, legacy, decrypted)
            assertArrayEquals(bytes, decrypted.readBytes())

            decrypted.delete()
            val damaged = legacy.readBytes()
            damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x10).toByte()
            legacy.writeBytes(damaged)
            // The platform cipher stream could report a failed tag as end of stream; the store
            // finishes the record explicitly, so nothing truncated reaches the target.
            assertTrue(runCatching { store.decryptFile(scope, legacy, decrypted) }.isFailure)
            assertFalse(decrypted.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            directory.deleteRecursively()
            store.deleteKey(scope)
        }
    }

    private fun ByteArray.containsSubsequence(value: ByteArray): Boolean {
        if (value.isEmpty()) return true
        return indices.any { start ->
            start + value.size <= size && value.indices.all { offset -> this[start + offset] == value[offset] }
        }
    }
}
