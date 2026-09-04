package com.bownee.lenswave.gallery

/** Decides what a gallery render has to rebuild, so the main thread never compares whole photo lists. */
internal object GalleryRenderPolicy {
    /**
     * Photo pages come from [GalleryAssetMemo], which returns the same instance while the page is
     * unchanged, so a reference check is the whole comparison: a structural compare would walk
     * every asset on the main thread on each miss. Library pages are small and rebuilt on every
     * publish, so those fall back to a structural compare.
     */
    fun contentChanged(
        rendered: GalleryContent?,
        next: GalleryContent,
    ): Boolean =
        when {
            rendered === next -> false
            next is GalleryContent.Library && rendered is GalleryContent.Library -> rendered != next
            else -> true
        }
}
