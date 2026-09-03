package com.bownee.lenswave.gallery

internal object GalleryFastScrollThumbnailPolicy {
    fun shouldReadSource(isFastScrolling: Boolean): Boolean = !isFastScrolling

    fun shouldRebind(
        wasFastScrolling: Boolean,
        isFastScrolling: Boolean,
    ): Boolean = wasFastScrolling && !isFastScrolling
}
