package com.bownee.lenswave.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test fun comparesNumericComponentsInsteadOfLexicographicText() {
        assertTrue(requireVersion("0.10.0") > requireVersion("0.9.0"))
        assertTrue(requireVersion("2.0.0") > requireVersion("1.99.99"))
        assertEquals(requireVersion("1.2.3"), requireVersion("v1.2.3+build.7"))
    }

    @Test fun stableVersionsSortAfterTheirPrereleases() {
        assertTrue(requireVersion("1.0.0") > requireVersion("1.0.0-rc.1"))
        assertTrue(requireVersion("1.0.0-rc.10") > requireVersion("1.0.0-rc.2"))
        assertTrue(requireVersion("1.0.0-beta") > requireVersion("1.0.0-2"))
    }

    @Test fun malformedVersionsAreRejected() {
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("01.2.3"))
        assertNull(SemanticVersion.parse("1.2.3-rc.01"))
        assertNull(SemanticVersion.parse("release-1.2.3"))
    }

    private fun requireVersion(value: String): SemanticVersion =
        requireNotNull(SemanticVersion.parse(value))
}
