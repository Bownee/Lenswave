package com.bownee.lenswave.gallery

/** Decides what a gallery render has to rebuild, so the main thread never compares whole photo lists. */
internal object GalleryRenderPolicy {
    /**
     * Every page comes from [GalleryAssetMemo], which returns the same instance while the page is
     * unchanged, so a reference check is the whole comparison: a structural compare would walk
     * every asset (or every album) on the main thread on each miss.
     */
    fun contentChanged(
        rendered: GalleryContent?,
        next: GalleryContent,
    ): Boolean = rendered !== next
}
