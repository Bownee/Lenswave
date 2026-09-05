package com.bownee.lenswave.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `write replaces existing contents and removes temporary file`() {
        val target = File(temporaryFolder.root, "index.json")

        AtomicFileStore.write(target, "first", "write failed")
        AtomicFileStore.write(target, "second", "write failed")

        assertEquals("second", target.readText())
        assertFalse(
            temporaryFolder.root
                .listFiles()
                .orEmpty()
                .any { it.name.endsWith(".part") },
        )
    }

    @Test
    fun `a synced write lands the contents and leaves no temporary file`() {
        val target = File(temporaryFolder.root, "queue.json")
        AtomicFileStore.write(target, "first", "write failed", fsync = true)

        AtomicFileStore.write(target, "second".toByteArray(), "write failed", fsync = true)

        assertEquals("second", target.readText())
        assertFalse(
            temporaryFolder.root
                .listFiles()
                .orEmpty()
                .any { it.name.endsWith(".part") },
        )
    }

    @Test
    fun `a synced write still honours the commit gate`() {
        val target = File(temporaryFolder.root, "queue.json")
        AtomicFileStore.write(target, "first", "write failed", fsync = true)
        var gated = false

        AtomicFileStore.write(target, "second".toByteArray(), "write failed", fsync = true) { commit ->
            gated = true
            commit()
        }

        assertTrue(gated)
        assertEquals("second", target.readText())
    }

    @Test
    fun `safe names are stable and contain no path separators`() {
        val safeName = AtomicFileStore.safeName("account/../photo")

        assertEquals(safeName, AtomicFileStore.safeName("account/../photo"))
        assertFalse(safeName.contains('/'))
        assertFalse(safeName.contains('\\'))
    }

    @Test
    fun `safe names are the lower-case hex SHA-256 of the value`() {
        // Stored files are addressed by these names, so the mapping must never change.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AtomicFileStore.safeName(""),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            AtomicFileStore.safeName("hello"),
        )
        assertEquals(64, AtomicFileStore.safeName("volume~node").length)
    }

    @Test
    fun `reusing the digest leaves every result independent of the previous one`() {
        val expected = java.security.MessageDigest.getInstance("SHA-256")
        val values = listOf("a", "b", "account/../photo", "a", "", "é中")

        values.forEach { value ->
            expected.reset()
            val reference = expected.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(value, reference, AtomicFileStore.safeName(value))
        }
    }

    @Test
    fun `safe names agree across threads`() {
        val value = "shared-node-uid"
        val expected = AtomicFileStore.safeName(value)
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = List(4) { Thread { repeat(50) { results += AtomicFileStore.safeName(value) } } }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(200, results.size)
        assertEquals(setOf(expected), results.toSet())
    }

    @Test
    fun `a refused commit gate leaves the target untouched and no temporary file`() {
        val target = File(temporaryFolder.root, "index.json")
        AtomicFileStore.write(target, "first", "write failed")

        val error =
            assertThrows(IllegalStateException::class.java) {
                AtomicFileStore.write(target, "second".toByteArray(), "write failed") { _ ->
                    throw IllegalStateException("gate refused")
                }
            }

        assertEquals("gate refused", error.message)
        assertEquals("first", target.readText())
        assertFalse(
            temporaryFolder.root
                .listFiles()
                .orEmpty()
                .any { it.name.endsWith(".part") },
        )
    }
}
