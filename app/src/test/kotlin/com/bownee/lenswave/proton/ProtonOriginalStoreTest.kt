package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import com.bownee.lenswave.storage.WrappingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Runs the real store over a temporary cache directory and the software crypto of [SecureFileStore]. */
class ProtonOriginalStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val wrappingKeys = FakeWrappingKeys()
    private val clock = FakeClock()
    private val failures = mutableListOf<Pair<LenswaveOperation, Throwable>>()
    private val store by lazy {
        ProtonOriginalStore(
            temporaryFolder.root,
            SecureFileStore(
                File(temporaryFolder.root, "keys"),
                wrappingKeys,
            ) { error -> failures += RECOVERY to error },
            clock,
            ProtonDecryptedCopyRegistry(),
        ) { operation, error -> failures += operation to error }
    }

    @Test
    fun `a committed download is encrypted and its plaintext moves to the shared path`() {
        val download = store.createTarget(USER, NODE)
        download.plaintext.writeText("original")

        val commit = store.commit(USER, NODE, download)

        assertTrue(commit.encryptedStored)
        assertEquals(decryptedCopy(), commit.plaintext)
        assertEquals("original", commit.plaintext.readText())
        assertFalse(download.plaintext.exists())
        assertTrue(download.encrypted.isFile)
        assertEquals(commit.plaintext, store.read(USER, NODE))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a download the cache cannot encrypt is still the viewer's to read and is found again`() {
        val download = store.createTarget(USER, NODE)
        download.plaintext.writeText("original")
        wrappingKeys.failWith = IllegalStateException("keystore unavailable")

        val commit = store.commit(USER, NODE, download)

        // Nothing encrypted landed, so there is nothing to account for; the plaintext moved to
        // the shared path all the same, and the next open within the TTL reads it instead of
        // downloading the original again.
        assertFalse(commit.encryptedStored)
        assertEquals(decryptedCopy(), commit.plaintext)
        assertEquals("original", commit.plaintext.readText())
        assertFalse(download.plaintext.exists())
        assertFalse(download.encrypted.exists())
        assertEquals(commit.plaintext, store.read(USER, NODE))
        assertTrue(failures.toString(), LenswaveOperation.ORIGINAL_CACHE_STORE in failures.map { it.first })
        // Once the copy has expired there is nothing cached at all.
        clock.value += ProtonStorageLayout.DECRYPTED_TTL_MILLIS + 1L
        assertNull(store.read(USER, NODE))
    }

    @Test
    fun `an encrypted original that landed counts even when the plaintext could not be moved`() {
        val download = store.createTarget(USER, NODE)
        download.plaintext.writeText("original")
        // A non-empty directory squats on the shared path, so the rename onto it fails.
        File(decryptedCopy(), "squatter").apply { parentFile?.mkdirs() }.writeText("x")

        val commit = store.commit(USER, NODE, download)

        assertTrue(commit.encryptedStored)
        assertTrue(download.encrypted.isFile)
        // The plaintext stays where the download wrote it, readable, rather than being lost.
        assertEquals(download.plaintext, commit.plaintext)
        assertEquals("original", commit.plaintext.readText())
        assertEquals(listOf(LenswaveOperation.ORIGINAL_CACHE_STORE), failures.map { it.first })
    }

    @Test
    fun `a download whose photo was removed meanwhile commits nothing, encrypted or not`() {
        val download = store.createTarget(USER, NODE)
        download.plaintext.writeText("original")
        store.remove(USER, NODE)

        assertThrows(ProtonOriginalRemovedException::class.java) { store.commit(USER, NODE, download) }

        assertFalse(download.plaintext.exists())
        assertFalse(download.encrypted.exists())
        assertFalse(decryptedCopy().exists())

        val unencrypted = store.createTarget(USER, NODE)
        unencrypted.plaintext.writeText("original")
        wrappingKeys.failWith = IllegalStateException("keystore unavailable")
        store.remove(USER, NODE)

        assertThrows(ProtonOriginalRemovedException::class.java) { store.commit(USER, NODE, unencrypted) }

        assertFalse(unencrypted.plaintext.exists())
        assertFalse(decryptedCopy().exists())
    }

    private fun decryptedCopy(): File =
        File(
            File(File(temporaryFolder.root, ProtonStorageLayout.DECRYPTED_DIRECTORY), AtomicFileStore.safeName(USER)),
            "${AtomicFileStore.safeName(NODE)}.image",
        )

    private class FakeWrappingKeys : WrappingKeys {
        private val keys = ConcurrentHashMap<String, SecretKey>()
        var failWith: Exception? = null

        override fun key(alias: String): SecretKey {
            failWith?.let { throw it }
            return keys.computeIfAbsent(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        }

        override fun delete(alias: String) {
            keys.remove(alias)
        }
    }

    private class FakeClock(
        var value: Long = 1_000L,
    ) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private companion object {
        const val USER = "user"
        const val NODE = "node"
        val RECOVERY = LenswaveOperation.DATA_KEY_RECOVERY
    }
}
