package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPullToRefreshPolicyTest {
    @Test
    fun `pull starts on the thumbnail area`() {
        assertTrue(GalleryPullToRefreshPolicy.startsPull(touchY = 400f, headerBottom = 200, filterRow = 200..260))
        assertTrue(GalleryPullToRefreshPolicy.startsPull(touchY = 261f, headerBottom = 200, filterRow = 200..260))
        assertTrue(GalleryPullToRefreshPolicy.startsPull(touchY = 200f, headerBottom = 200, filterRow = null))
    }

    @Test
    fun `pull does not start on the pinned header`() {
        assertFalse(GalleryPullToRefreshPolicy.startsPull(touchY = 100f, headerBottom = 200, filterRow = null))
        assertFalse(GalleryPullToRefreshPolicy.startsPull(touchY = 199.9f, headerBottom = 200, filterRow = 200..260))
    }

    @Test
    fun `pull does not start on the filter chips`() {
        assertFalse(GalleryPullToRefreshPolicy.startsPull(touchY = 230f, headerBottom = 200, filterRow = 200..260))
        assertFalse(GalleryPullToRefreshPolicy.startsPull(touchY = 260f, headerBottom = 200, filterRow = 200..260))
    }

    @Test
    fun `pull does not start in the gap below the filter chips`() {
        assertFalse(
            GalleryPullToRefreshPolicy.startsPull(
                touchY = 261f,
                headerBottom = 200,
                filterRow = 200..260,
                gapBelowFilterRow = 40,
            ),
        )
        assertFalse(
            GalleryPullToRefreshPolicy.startsPull(
                touchY = 300f,
                headerBottom = 200,
                filterRow = 200..260,
                gapBelowFilterRow = 40,
            ),
        )
        assertTrue(
            GalleryPullToRefreshPolicy.startsPull(
                touchY = 301f,
                headerBottom = 200,
                filterRow = 200..260,
                gapBelowFilterRow = 40,
            ),
        )
    }

    @Test
    fun `gap without a filter row does not block the pull`() {
        assertTrue(
            GalleryPullToRefreshPolicy.startsPull(
                touchY = 210f,
                headerBottom = 200,
                filterRow = null,
                gapBelowFilterRow = 40,
            ),
        )
    }

    @Test
    fun `filter chips scrolled under the header stay covered by the header rule`() {
        assertFalse(GalleryPullToRefreshPolicy.startsPull(touchY = 150f, headerBottom = 200, filterRow = 120..180))
    }
}
