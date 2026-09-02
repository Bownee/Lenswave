package com.bownee.lenswave.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitHubReleaseJsonInstrumentedTest {
    @Test fun readsTagNameWithoutDependingOnFieldOrder() {
        val json = """{"name":"Lenswave 0.20.0","draft":false,"tag_name":"v0.20.0"}"""

        val versionName = GitHubReleaseJson.readVersionName(
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        )

        assertEquals("v0.20.0", versionName)
    }
}
