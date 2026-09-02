package com.bownee.lenswave.gallery

import me.proton.core.domain.entity.UserId

internal data class GalleryThumbnailCacheIdentity(
    val deviceAccessLevel: DeviceAccessLevel,
    val protonUserId: UserId?,
)

internal object GalleryThumbnailCachePolicy {
    fun shouldInvalidate(
        previous: GalleryThumbnailCacheIdentity?,
        current: GalleryThumbnailCacheIdentity,
    ): Boolean = previous != null && previous != current
}
