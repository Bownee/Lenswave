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
    fun `key aliases are listed by scope family and go with the key`() {
        val store = store()
        val userA = "proton-media:user-a-${UUID.randomUUID()}"
        val userB = "proton-media:user-b-${UUID.randomUUID()}"
        val session = "proton-session-database-key-${UUID.randomUUID()}"
        listOf(userA, userB, session).forEach { scope ->
            store.write(scope, File(temporaryFolder.root, "${scope.hashCode()}.bin"), byteArrayOf(1), "write failed")
        }

        assertEquals(
            setOf(store.keyAlias(userA), store.keyAlias(userB)),
            store.keyAliases("proton-media").toSet(),
        )
        assertEquals(emptyList<String>(), store.keyAliases("proton-session-database-key"))
        assertEquals(listOf(store.keyAlias(session)), store.keyAliases(session))

        store.deleteKeyAlias(store.keyAlias(userB))

        assertEquals(listOf(store.keyAlias(userA)), store.keyAliases("proton-media"))
        assertTrue(
            File(temporaryFolder.root, "keys")
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith(store.keyAlias(userB)) },
        )
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

    @Test
    fun `a small whole-file legacy record decrypts in one piece`() {
        val store = store()
        val legacy = File(temporaryFolder.root, "legacy")
        val decrypted = File(temporaryFolder.root, "decrypted")
        val bytes = ByteArray(50_000) { (it * 3).toByte() }
        // A small payload is one whole-file GCM record: exactly the layout of a version 2 original.
        store.write(scope, legacy, bytes, "write failed")

        store.decryptFile(scope, legacy, decrypted)

        assertArrayEquals(bytes, decrypted.readBytes())
    }

    @Test
    fun `a flipped byte in a legacy record throws instead of committing a truncated plaintext`() {
        val store = store()
        val legacy = File(temporaryFolder.root, "legacy")
        val decrypted = File(temporaryFolder.root, "decrypted")
        store.write(scope, legacy, ByteArray(50_000) { (it * 3).toByte() }, "write failed")
        val damaged = legacy.readBytes()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x10).toByte()
        legacy.writeBytes(damaged)

        assertThrows(AEADBadTagException::class.java) { store.decryptFile(scope, legacy, decrypted) }

        assertFalse(decrypted.exists())
        assertTrue(legacy.isFile)
        assertTrue(
            temporaryFolder.root
                .listFiles()
                .orEmpty()
                .none { it.name.endsWith(".part") },
        )
    }

    @Test
    fun `an oversized legacy record is discarded as corrupt rather than decrypted in memory`() {
        val store = store()
        val legacy = File(temporaryFolder.root, "legacy")
        val decrypted = File(temporaryFolder.root, "decrypted")
        // The version 2 header followed by more than the limit: the size check runs before any
        // ciphertext is read, so the body need not verify.
        val header = byteArrayOf(0x4c, 0x57, 0x45, 0x46, 2, 12) + ByteArray(12)
        legacy.outputStream().use { output ->
            output.write(header)
            val chunk = ByteArray(1 shl 20)
            repeat(9) { output.write(chunk) }
        }

        assertThrows(IllegalArgumentException::class.java) { store.decryptFile(scope, legacy, decrypted) }

        assertFalse(legacy.exists())
        assertFalse(decrypted.exists())
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
