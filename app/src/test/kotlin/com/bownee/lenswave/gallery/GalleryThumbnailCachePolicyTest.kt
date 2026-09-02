package com.bownee.lenswave.gallery

import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryThumbnailCachePolicyTest {
    @Test
    fun initialAndUnchangedIdentityPreserveThumbnails() {
        val identity = identity()

        assertFalse(GalleryThumbnailCachePolicy.shouldInvalidate(null, identity))
        assertFalse(GalleryThumbnailCachePolicy.shouldInvalidate(identity, identity))
    }

    @Test
    fun changedDeviceAccessInvalidatesThumbnails() {
        assertTrue(
            GalleryThumbnailCachePolicy.shouldInvalidate(
                identity(deviceAccessLevel = DeviceAccessLevel.FULL),
                identity(deviceAccessLevel = DeviceAccessLevel.PARTIAL),
            ),
        )
    }

    @Test
    fun changedProtonAccountInvalidatesThumbnails() {
        assertTrue(
            GalleryThumbnailCachePolicy.shouldInvalidate(
                identity(protonUserId = UserId("first-user")),
                identity(protonUserId = UserId("second-user")),
            ),
        )
    }

    private fun identity(
        deviceAccessLevel: DeviceAccessLevel = DeviceAccessLevel.FULL,
        protonUserId: UserId? = UserId("user"),
    ) = GalleryThumbnailCacheIdentity(deviceAccessLevel, protonUserId)
}
