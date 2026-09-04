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
import javax.crypto.AEADBadTagException

class DatabasePassphraseStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val payloads = FakePayloadStore()
    private val reported = mutableListOf<Throwable>()

    private fun store(): DatabasePassphraseStore =
        DatabasePassphraseStore(File(temporaryFolder.root, "session.key"), payloads, SCOPE, reported::add)

    @Test
    fun `a passphrase is minted once and read back afterwards`() {
        val store = store()

        val first = store.getOrCreate()
        val second = store.getOrCreate()

        assertEquals(32, first.bytes.size)
        assertFalse(first.replacedUnreadable)
        assertArrayEquals(first.bytes, second.bytes)
        assertFalse(second.replacedUnreadable)
        assertEquals(1, payloads.writes)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `an unreadable passphrase is reported, discarded and replaced`() {
        val store = store()
        val original = store.getOrCreate().bytes
        payloads.failReadsWith = AEADBadTagException("tag mismatch")

        val replacement = store.getOrCreate()

        assertTrue(replacement.replacedUnreadable)
        assertFalse(original.contentEquals(replacement.bytes))
        assertEquals(2, payloads.writes)
        assertTrue(reported.single() is AEADBadTagException)
        // The fresh passphrase is stored: the next read gets it back without another replacement.
        payloads.failReadsWith = null
        val again = store.getOrCreate()
        assertArrayEquals(replacement.bytes, again.bytes)
        assertFalse(again.replacedUnreadable)
    }

    @Test
    fun `a malformed passphrase file counts as unreadable`() {
        val store = store()
        store.getOrCreate()
        payloads.failReadsWith = IllegalArgumentException("Encrypted file header is invalid")

        assertTrue(store.getOrCreate().replacedUnreadable)
        assertTrue(reported.single() is IllegalArgumentException)
    }

    @Test
    fun `an unavailable data key is left alone and propagates`() {
        val store = store()
        val original = store.getOrCreate().bytes
        payloads.failReadsWith = SecureFileStore.DataKeyUnavailableException(IllegalStateException("keystore busy"))

        assertThrows(SecureFileStore.DataKeyUnavailableException::class.java) { store.getOrCreate() }

        assertTrue(reported.isEmpty())
        assertEquals(1, payloads.writes)
        payloads.failReadsWith = null
        assertArrayEquals(original, store.getOrCreate().bytes)
    }

    /** Stores payloads in plain files; the read failure it is told to raise stands in for a bad tag. */
    private class FakePayloadStore : SecurePayloadStore {
        var failReadsWith: Exception? = null
        var writes = 0

        override fun read(
            scope: String,
            file: File,
        ): ByteArray {
            assertEquals(SCOPE, scope)
            failReadsWith?.let { throw it }
            return file.readBytes()
        }

        override fun write(
            scope: String,
            file: File,
            bytes: ByteArray,
            failureMessage: String,
        ) {
            assertEquals(SCOPE, scope)
            writes++
            file.writeBytes(bytes)
        }
    }

    private companion object {
        const val SCOPE = "test-session-database-key"
    }
}
