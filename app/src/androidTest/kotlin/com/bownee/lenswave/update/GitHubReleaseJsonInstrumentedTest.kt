package com.bownee.lenswave.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class GitHubReleaseJsonInstrumentedTest {
    @Test fun readsTagNameWithoutDependingOnFieldOrder() {
        val json = """{"name":"Lenswave 0.20.0","draft":false,"tag_name":"v0.20.0"}"""

        val versionName =
            GitHubReleaseJson.readVersionName(
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            )

        assertEquals("v0.20.0", versionName)
    }

    @Test fun rejectsTagNameLongerThanAReleaseTag() {
        val json = """{"tag_name":"v${"0".repeat(ReleaseTagPolicy.MAX_TAG_LENGTH)}"}"""

        val versionName =
            GitHubReleaseJson.readVersionName(
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            )

        assertNull(versionName)
    }
}
