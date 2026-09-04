package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.GalleryAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhotoNavigationSourcesTest {
    @Test
    fun `every published list stays reachable by its own token until cleared`() {
        val first = listOf(asset("a"))
        val second = listOf(asset("b"))

        val firstToken = PhotoNavigationSources.publish("u", first)
        val secondToken = PhotoNavigationSources.publish("u", second)

        assertSame(first, PhotoNavigationSources.find(firstToken)?.assets)
        assertSame(second, PhotoNavigationSources.find(secondToken)?.assets)

        PhotoNavigationSources.clear(firstToken)
        assertNull(PhotoNavigationSources.find(firstToken))
        assertEquals("u", PhotoNavigationSources.find(secondToken)?.userId)
        PhotoNavigationSources.clear(secondToken)
    }

    @Test
    fun `only a few lists are retained for viewers that never cleared theirs`() {
        val tokens = (1..6).map { index -> PhotoNavigationSources.publish("u", listOf(asset("p$index"))) }

        assertNull(PhotoNavigationSources.find(tokens[0]))
        assertNull(PhotoNavigationSources.find(tokens[1]))
        tokens.drop(2).forEach { token -> assertEquals(token, PhotoNavigationSources.find(token)?.token) }
        tokens.forEach(PhotoNavigationSources::clear)
    }

    @Test
    fun `clearing a token that resolves to nothing, or no token at all, is harmless`() {
        val token = PhotoNavigationSources.publish("u", listOf(asset("kept")))

        PhotoNavigationSources.clear(PhotoNavigationSources.NO_TOKEN)
        PhotoNavigationSources.clear(token + 1_000)

        assertEquals(token, PhotoNavigationSources.find(token)?.token)
        // A viewer clears the token its intent carried when it finishes, even after it rebuilt
        // its own source and never adopted this list; clearing twice must not matter either.
        PhotoNavigationSources.clear(token)
        PhotoNavigationSources.clear(token)
        assertNull(PhotoNavigationSources.find(token))
    }

    private fun asset(stableId: String) =
        GalleryAsset(stableId = stableId, capturedAtEpochMillis = 0L, nodeUid = stableId, hasThumbnail = true)
}
