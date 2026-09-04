package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSyncPolicyTest {
    @Test
    fun `fresh cached snapshot avoids a full enumeration`() {
        val now = 1_000_000L

        assertFalse(
            ProtonSyncPolicy.shouldEnumerate(
                source = ProtonSyncSource.TIMELINE,
                lastSuccessfulSyncMillis = now - 1_000L,
                nowMillis = now,
                forceRemote = false,
                hasCachedSnapshot = true,
            ),
        )
    }

    @Test
    fun `manual refresh always enumerates`() {
        assertTrue(
            ProtonSyncPolicy.shouldEnumerate(
                source = ProtonSyncSource.ALBUMS,
                lastSuccessfulSyncMillis = 1_000L,
                nowMillis = 2_000L,
                forceRemote = true,
                hasCachedSnapshot = true,
            ),
        )
    }

    @Test
    fun `missing stale or future metadata enumerates`() {
        val now = 10_000_000L

        assertTrue(
            ProtonSyncPolicy.shouldEnumerate(
                ProtonSyncSource.ALBUM_PHOTOS,
                lastSuccessfulSyncMillis = now,
                nowMillis = now,
                forceRemote = false,
                hasCachedSnapshot = false,
            ),
        )
        assertTrue(
            ProtonSyncPolicy.shouldEnumerate(
                ProtonSyncSource.TIMELINE,
                lastSuccessfulSyncMillis = now - ProtonSyncSource.TIMELINE.maximumAgeMillis,
                nowMillis = now,
                forceRemote = false,
                hasCachedSnapshot = true,
            ),
        )
        assertTrue(
            ProtonSyncPolicy.shouldEnumerate(
                ProtonSyncSource.TIMELINE,
                lastSuccessfulSyncMillis = now + 1L,
                nowMillis = now,
                forceRemote = false,
                hasCachedSnapshot = true,
            ),
        )
    }
}
