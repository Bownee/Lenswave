package com.bownee.lenswave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseEtagPolicyTest {
    @Test
    fun `plausible entity tags are kept as they are`() {
        listOf("\"abc123\"", "W/\"0815-etag_v2.1+x\"", "abc", "\"a:b=c\"").forEach { etag ->
            assertEquals(etag, ReleaseEtagPolicy.accept(etag))
        }
        assertEquals(
            "a".repeat(ReleaseEtagPolicy.MAX_LENGTH),
            ReleaseEtagPolicy.accept("a".repeat(ReleaseEtagPolicy.MAX_LENGTH)),
        )
    }

    @Test
    fun `missing, empty, oversized and odd-charactered tags are dropped`() {
        assertNull(ReleaseEtagPolicy.accept(null))
        assertNull(ReleaseEtagPolicy.accept(""))
        assertNull(ReleaseEtagPolicy.accept("a".repeat(ReleaseEtagPolicy.MAX_LENGTH + 1)))
        val nul = 0.toChar()
        val del = 127.toChar()
        listOf("with space", "tab\tin", "new\nline", "nul$nul", "café", "\"$del\"").forEach { etag ->
            assertNull(etag, ReleaseEtagPolicy.accept(etag))
        }
    }
}
