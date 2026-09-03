package com.bownee.lenswave.gallery

import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryThumbnailCachePolicyTest {
    @Test
    fun initialAndUnchangedIdentityPreserveThumbnails() {
        val identity = GalleryThumbnailCacheIdentity(UserId("user"))

        assertFalse(GalleryThumbnailCachePolicy.shouldInvalidate(null, identity))
        assertFalse(GalleryThumbnailCachePolicy.shouldInvalidate(identity, identity))
    }

    @Test
    fun changedProtonAccountInvalidatesThumbnails() {
        assertTrue(
            GalleryThumbnailCachePolicy.shouldInvalidate(
                GalleryThumbnailCacheIdentity(UserId("first-user")),
                GalleryThumbnailCacheIdentity(UserId("second-user")),
            ),
        )
        assertTrue(
            GalleryThumbnailCachePolicy.shouldInvalidate(
                GalleryThumbnailCacheIdentity(UserId("first-user")),
                GalleryThumbnailCacheIdentity(null),
            ),
        )
    }
}
