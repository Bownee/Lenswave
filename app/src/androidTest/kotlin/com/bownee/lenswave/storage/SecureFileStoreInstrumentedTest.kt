package com.bownee.lenswave.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecureFileStoreInstrumentedTest {
    @Test
    fun payloadIsAuthenticatedEncryptedAndDestroyedWithItsKey() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureFileStore()
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

    private fun ByteArray.containsSubsequence(value: ByteArray): Boolean {
        if (value.isEmpty()) return true
        return indices.any { start ->
            start + value.size <= size && value.indices.all { offset -> this[start + offset] == value[offset] }
        }
    }
}
