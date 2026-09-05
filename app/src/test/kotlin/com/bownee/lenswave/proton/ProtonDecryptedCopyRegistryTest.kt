package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtonDecryptedCopyRegistryTest {
    private val registry = ProtonDecryptedCopyRegistry()
    private val copy = File("decrypted/user/video.image")

    @Test
    fun `a copy is in use from the first open until the last close`() {
        assertFalse(registry.isInUse(copy))

        registry.opened(copy)
        registry.opened(File("decrypted/user/video.image"))
        assertTrue(registry.isInUse(copy))

        registry.closed(copy)
        assertTrue(registry.isInUse(copy))
        registry.closed(copy)
        assertFalse(registry.isInUse(copy))
    }

    @Test
    fun `a close without an open and other copies are ignored`() {
        registry.closed(copy)
        registry.opened(File("decrypted/user/other.image"))

        assertFalse(registry.isInUse(copy))
    }
}
