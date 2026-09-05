package com.bownee.lenswave.viewer

/**
 * Whether the original being decrypted ahead of a swipe is the one a photo needs. The viewer
 * keeps such a prefetch across the swipe onto that photo and awaits it, because cancelling it
 * deletes the partial plaintext and the photo's own probe would decrypt from the start again;
 * a prefetch of any other neighbour is cancelled as before.
 */
internal object ViewerPrefetchPolicy {
    fun isFor(
        prefetchedStableId: String?,
        stableId: String,
    ): Boolean = prefetchedStableId != null && prefetchedStableId == stableId
}
