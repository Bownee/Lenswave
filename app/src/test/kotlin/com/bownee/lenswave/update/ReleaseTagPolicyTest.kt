package com.bownee.lenswave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTagPolicyTest {
    @Test
    fun `a short tag passes through unchanged`() {
        assertEquals("v0.20.0", ReleaseTagPolicy.accept("v0.20.0"))
        assertEquals("a".repeat(ReleaseTagPolicy.MAX_TAG_LENGTH), ReleaseTagPolicy.accept("a".repeat(64)))
    }

    @Test
    fun `a missing, empty or overlong tag is rejected`() {
        assertNull(ReleaseTagPolicy.accept(null))
        assertNull(ReleaseTagPolicy.accept(""))
        assertNull(ReleaseTagPolicy.accept("v" + "0".repeat(ReleaseTagPolicy.MAX_TAG_LENGTH)))
    }
}
