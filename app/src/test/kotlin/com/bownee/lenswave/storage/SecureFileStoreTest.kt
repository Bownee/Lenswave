package com.bownee.lenswave.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Runs the store's software crypto against in-memory wrapping keys. A second instance over the
 * same key directory stands in for a process restart: it starts with no unwrapped key in memory.
 */
class SecureFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val wrappingKeys = FakeWrappingKeys()
    private val reported = mutableListOf<Throwable>()
    private val scope = "test-${UUID.randomUUID()}"

    private fun store(): SecureFileStore =
        SecureFileStore(File(temporaryFolder.root, "keys"), wrappingKeys, reported::add)

    @Test
    fun `small payloads round trip and are authenticated`() {
        val store = store()
        val target = File(temporaryFolder.root, "payload.bin")
        val plaintext = "session passphrase".toByteArray()

        store.write(scope, target, plaintext, "write failed")

        assertArrayEquals(plaintext, store.read(scope, target))
        val damaged = target.readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        target.writeBytes(damaged)
        assertThrows(AEADBadTagException::class.java) { store.read(scope, target) }
    }

    @Test
    fun `a wrapped key the keystore can no longer unwrap is discarded and its files become unreadable`() {
        val store = store()
        val target = File(temporaryFolder.root, "payload.bin")
        store.write(scope, target, "before".toByteArray(), "write failed")
        // The Keystore entry vanishes (a reset, a restored backup) while the wrapped file stays,
        // and a new process starts with no unwrapped key in memory.
        wrappingKeys.forget(store.keyAlias(scope))
        val restarted = store()

        assertThrows(AEADBadTagException::class.java) { restarted.read(scope, target) }

        assertTrue(reported.single() is AEADBadTagException)
        // The scope works again with a fresh key; only what the old key protected is lost.
        restarted.write(scope, target, "after".toByteArray(), "write failed")
        assertArrayEquals("after".toByteArray(), restarted.read(scope, target))
    }

    @Test
    fun `a keystore that refuses to answer surfaces as an unavailable key, not as corruption`() {
        val store = store()
        val target = File(temporaryFolder.root, "payload.bin")
        store.write(scope, target, "kept".toByteArray(), "write failed")
        val restarted = store()
        wrappingKeys.failWith = IllegalStateException("keystore is busy")

        assertThrows(SecureFileStore.DataKeyUnavailableException::class.java) { restarted.read(scope, target) }

        assertTrue(reported.isEmpty())
        wrappingKeys.failWith = null
        assertArrayEquals("kept".toByteArray(), restarted.read(scope, target))
    }

    @Test
    fun `deleting the key removes the wrapper and the keystore entry`() {
        val store = store()
        val target = File(temporaryFolder.root, "payload.bin")
        store.write(scope, target, "gone".toByteArray(), "write failed")
        val alias = store.keyAlias(scope)

        store.deleteKey(scope)

        assertFalse(File(File(temporaryFolder.root, "keys"), "$alias.key").exists())
        assertFalse(wrappingKeys.holds(alias))
        assertEquals(1, wrappingKeys.deleted)
        assertThrows(AEADBadTagException::class.java) { store.read(scope, target) }
    }

    @Test
    fun `whole files round trip through the segmented envelope`() {
        val store = store()
        val plaintext =
            File(
                temporaryFolder.root,
                "original",
            ).apply { writeBytes(ByteArray(300_000) { (it * 7).toByte() }) }
        val encrypted = File(temporaryFolder.root, "encrypted")
        val decrypted = File(temporaryFolder.root, "decrypted")

        store.encryptFile(scope, plaintext, encrypted, "encrypt failed")
        store.decryptFile(scope, encrypted, decrypted)

        assertArrayEquals(plaintext.readBytes(), decrypted.readBytes())
        assertTrue(
            temporaryFolder.root
                .listFiles()
                .orEmpty()
                .none { it.name.endsWith(".part") },
        )
    }

    class FakeWrappingKeys : WrappingKeys {
        private val keys = ConcurrentHashMap<String, SecretKey>()
        var failWith: Exception? = null
        var deleted = 0

        override fun key(alias: String): SecretKey {
            failWith?.let { throw it }
            return keys.computeIfAbsent(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        }

        override fun delete(alias: String) {
            deleted++
            keys.remove(alias)
        }

        fun forget(alias: String) {
            keys.remove(alias)
        }

        fun holds(alias: String): Boolean = keys.containsKey(alias)
    }
}
