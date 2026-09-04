package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.viewer.PhotoNavigationWindowPolicy.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoNavigationWindowPolicyTest {
    @Test
    fun `the initial window is the radius either side, clamped to the list`() {
        assertEquals(Window(80, 121), PhotoNavigationWindowPolicy.initial(currentIndex = 100, listSize = 1_000))
        assertEquals(Window(0, 24), PhotoNavigationWindowPolicy.initial(currentIndex = 3, listSize = 1_000))
        assertEquals(Window(977, 1_000), PhotoNavigationWindowPolicy.initial(currentIndex = 997, listSize = 1_000))
        assertEquals(Window(0, 5), PhotoNavigationWindowPolicy.initial(currentIndex = 2, listSize = 5))
        assertEquals(Window(0, 0), PhotoNavigationWindowPolicy.initial(currentIndex = 0, listSize = 0))
        assertEquals(41, PhotoNavigationWindowPolicy.initial(currentIndex = 100, listSize = 1_000).size)
    }

    @Test
    fun `a tapped index that names the photo is trusted, anything else falls back to the search`() {
        val assets = listOf(asset("a"), asset("b"), asset("c"))

        assertEquals(1, PhotoNavigationWindowPolicy.currentIndex(assets, "b", hint = 1))
        assertEquals(2, PhotoNavigationWindowPolicy.currentIndex(assets, "c", hint = 0))
        assertEquals(0, PhotoNavigationWindowPolicy.currentIndex(assets, "a", hint = -1))
        assertEquals(1, PhotoNavigationWindowPolicy.currentIndex(assets, "b", hint = 7))
        assertEquals(0, PhotoNavigationWindowPolicy.currentIndex(assets, "missing", hint = 2))
        assertEquals(0, PhotoNavigationWindowPolicy.currentIndex(emptyList(), "a", hint = 0))
    }

    private fun asset(stableId: String) =
        GalleryAsset(stableId = stableId, capturedAtEpochMillis = 0L, nodeUid = stableId, hasThumbnail = true)

    @Test
    fun `an out-of-range index is clamped into the list`() {
        assertEquals(Window(0, 21), PhotoNavigationWindowPolicy.initial(currentIndex = -4, listSize = 1_000))
        assertEquals(Window(979, 1_000), PhotoNavigationWindowPolicy.initial(currentIndex = 5_000, listSize = 1_000))
    }

    @Test
    fun `the window stays as it is while the current photo is well inside it`() {
        val window = Window(80, 121)
        assertNull(PhotoNavigationWindowPolicy.extended(window, currentIndex = 100, listSize = 1_000))
        assertNull(PhotoNavigationWindowPolicy.extended(window, currentIndex = 86, listSize = 1_000))
        assertNull(PhotoNavigationWindowPolicy.extended(window, currentIndex = 114, listSize = 1_000))
    }

    @Test
    fun `the window grows forward before the current photo reaches its end`() {
        val window = Window(80, 121)
        assertEquals(
            Window(80, 141),
            PhotoNavigationWindowPolicy.extended(window, currentIndex = 115, listSize = 1_000),
        )
        assertEquals(
            Window(80, 141),
            PhotoNavigationWindowPolicy.extended(window, currentIndex = 120, listSize = 1_000),
        )
    }

    @Test
    fun `the window grows backward before the current photo reaches its start`() {
        val window = Window(80, 121)
        assertEquals(Window(60, 121), PhotoNavigationWindowPolicy.extended(window, currentIndex = 85, listSize = 1_000))
        assertEquals(Window(60, 121), PhotoNavigationWindowPolicy.extended(window, currentIndex = 80, listSize = 1_000))
    }

    @Test
    fun `growth is clamped to the list and stops at its ends`() {
        assertEquals(
            Window(0, 121),
            PhotoNavigationWindowPolicy.extended(Window(10, 121), currentIndex = 12, listSize = 1_000),
        )
        assertEquals(
            Window(80, 130),
            PhotoNavigationWindowPolicy.extended(Window(80, 121), currentIndex = 118, listSize = 130),
        )
        // At the very end of the list there is nothing more to add.
        assertNull(PhotoNavigationWindowPolicy.extended(Window(80, 130), currentIndex = 129, listSize = 130))
        assertNull(PhotoNavigationWindowPolicy.extended(Window(0, 41), currentIndex = 0, listSize = 1_000))
    }

    @Test
    fun `a current photo outside the window changes nothing`() {
        assertNull(PhotoNavigationWindowPolicy.extended(Window(80, 121), currentIndex = 200, listSize = 1_000))
        assertNull(PhotoNavigationWindowPolicy.extended(Window(80, 121), currentIndex = 121, listSize = 1_000))
    }
}
