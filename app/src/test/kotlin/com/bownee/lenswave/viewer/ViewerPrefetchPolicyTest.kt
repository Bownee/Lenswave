package com.bownee.lenswave.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPrefetchPolicyTest {
    @Test
    fun `a prefetch of the swipe target is kept and awaited`() {
        assertTrue(ViewerPrefetchPolicy.isFor(prefetchedStableId = "photo-2", stableId = "photo-2"))
    }

    @Test
    fun `a prefetch of the other neighbour is cancelled`() {
        assertFalse(ViewerPrefetchPolicy.isFor(prefetchedStableId = "photo-0", stableId = "photo-2"))
    }

    @Test
    fun `no prefetch means nothing to keep`() {
        assertFalse(ViewerPrefetchPolicy.isFor(prefetchedStableId = null, stableId = "photo-2"))
    }
}
