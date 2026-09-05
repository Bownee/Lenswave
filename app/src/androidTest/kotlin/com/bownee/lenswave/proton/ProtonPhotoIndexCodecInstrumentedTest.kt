package com.bownee.lenswave.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The legacy path parses with the platform's `org.json`, which the JVM tests do not have. */
@RunWith(AndroidJUnit4::class)
class ProtonPhotoIndexCodecInstrumentedTest {
    @Test
    fun theJsonFormEarlierReleasesWroteIsStillRead() {
        val json = """[{"nodeUid":"v~a1","captureTime":100},{"nodeUid":"v~b2","captureTime":-5}]"""
        val bytes = json.toByteArray(Charsets.UTF_8)

        assertTrue(ProtonPhotoIndexCodec.isLegacyJson(bytes))
        val decoded = ProtonPhotoIndexCodec.decode(bytes) { nodeUid, captureTime -> nodeUid to captureTime }
        assertEquals(listOf("v~a1" to 100L, "v~b2" to -5L), decoded)
    }
}
