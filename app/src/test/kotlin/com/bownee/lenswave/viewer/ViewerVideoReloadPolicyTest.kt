package com.bownee.lenswave.viewer

import com.bownee.lenswave.proton.ProtonOriginalCopyMissingException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class ViewerVideoReloadPolicyTest {
    @Test
    fun `a missing file anywhere in the cause chain is a vanished copy`() {
        assertTrue(ViewerVideoReloadPolicy.copyMissing(FileNotFoundException("gone")))
        assertTrue(ViewerVideoReloadPolicy.copyMissing(RuntimeException(IOException(FileNotFoundException("gone")))))
        assertTrue(
            ViewerVideoReloadPolicy.copyMissing(
                RuntimeException(ProtonOriginalCopyMissingException(FileNotFoundException("gone"))),
            ),
        )
    }

    @Test
    fun `other errors and a cause cycle are not`() {
        assertFalse(ViewerVideoReloadPolicy.copyMissing(IOException("read failed")))
        assertFalse(ViewerVideoReloadPolicy.copyMissing(IllegalStateException("decoder")))
        val cyclic = RuntimeException("a")
        cyclic.initCause(RuntimeException("b", cyclic))
        assertFalse(ViewerVideoReloadPolicy.copyMissing(cyclic))
    }

    @Test
    fun `a complete stream whose copy vanished reloads once`() {
        val error = RuntimeException(FileNotFoundException("gone"))

        assertTrue(ViewerVideoReloadPolicy.reloads(error, streamComplete = true, alreadyReloaded = false))
        assertFalse(
            "once per request",
            ViewerVideoReloadPolicy.reloads(error, streamComplete = true, alreadyReloaded = true),
        )
        assertFalse(
            "mid-download the missing file is the download's failure",
            ViewerVideoReloadPolicy.reloads(error, streamComplete = false, alreadyReloaded = false),
        )
        assertFalse(
            ViewerVideoReloadPolicy.reloads(IOException("read"), streamComplete = true, alreadyReloaded = false),
        )
    }
}
