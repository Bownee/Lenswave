package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonAppVersionPolicyTest {
    /** The shape Proton Core accepts: a name, the numeric version, at most one suffix. */
    private val accepted = Regex("""^[a-z-]+@\d+\.\d+\.\d+(-[a-z0-9]+)?$""")

    @Test
    fun `a release version name carries the stage tag`() {
        assertEquals("external-drive-lenswave@1.0.0-alpha", ProtonAppVersionPolicy.header("1.0.0"))
    }

    @Test
    fun `a pre-release tag or a variant suffix is dropped rather than doubled`() {
        assertEquals("external-drive-lenswave@1.0.0-alpha", ProtonAppVersionPolicy.header("1.0.0-rc1"))
        assertEquals("external-drive-lenswave@1.2.3-alpha", ProtonAppVersionPolicy.header("1.2.3-minified"))
        assertEquals("external-drive-lenswave@1.2.3-alpha", ProtonAppVersionPolicy.header("1.2.3-rc.1-minified"))
    }

    @Test
    fun `every header has the shape Proton accepts`() {
        listOf("1.0.0", "1.0.0-rc1", "12.34.56-minified", "1.2.3-rc.1-minified", "garbage").forEach { versionName ->
            val header = ProtonAppVersionPolicy.header(versionName)
            assertTrue("'$header' from '$versionName'", accepted.matches(header))
        }
    }

    @Test
    fun `a version name without a numeric core falls back instead of crashing sign-in`() {
        assertEquals("external-drive-lenswave@0.0.0-alpha", ProtonAppVersionPolicy.header("garbage"))
    }
}
