package com.bownee.lenswave.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `write replaces existing contents and removes temporary file`() {
        val target = File(temporaryFolder.root, "index.json")

        AtomicFileStore.write(target, "first", "write failed")
        AtomicFileStore.write(target, "second", "write failed")

        assertEquals("second", target.readText())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun `safe names are stable and contain no path separators`() {
        val safeName = AtomicFileStore.safeName("account/../photo")

        assertEquals(safeName, AtomicFileStore.safeName("account/../photo"))
        assertFalse(safeName.contains('/'))
        assertFalse(safeName.contains('\\'))
    }
}
