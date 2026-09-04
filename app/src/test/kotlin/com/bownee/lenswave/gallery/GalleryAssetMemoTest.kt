package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTagState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAssetMemoTest {
    private val memo = GalleryAssetMemo()
    private val video = ProtonGalleryPhoto("video", 20, hasThumbnail = true)
    private val image = ProtonGalleryPhoto("image", 10, hasThumbnail = true)
    private val undated = ProtonGalleryPhoto("undated", 0, hasThumbnail = false)

    @Test
    fun `same source lists yield the same content instance`() {
        val photos = listOf(image, video)
        val tags = emptyMap<ProtonMediaTag, ProtonTagState>()

        val first = memo.photos(photos, memo.tagIndex(tags))
        val second = memo.photos(photos, memo.tagIndex(tags))

        assertSame(first, second)
    }

    @Test
    fun `a new photo list is mapped and sorted again`() {
        val tagIndex = memo.tagIndex(emptyMap())
        val first = memo.photos(listOf(image, video), tagIndex)
        val second = memo.photos(listOf(undated, image, video), tagIndex)

        assertNotSame(first, second)
        assertEquals(listOf("proton:video", "proton:image", "proton:undated"), second.assets.map { it.stableId })
    }

    @Test
    fun `a new tag index remaps the same photo list`() {
        val photos = listOf(image, video)
        val first = memo.photos(photos, memo.tagIndex(emptyMap()))
        val tagged =
            memo.tagIndex(
                mapOf(
                    ProtonMediaTag.VIDEOS to ProtonTagState(listOf(video), hasLoaded = true),
                    ProtonMediaTag.FAVORITES to ProtonTagState(listOf(video, image), hasLoaded = true),
                ),
            )
        val second = memo.photos(photos, tagged)

        assertNotSame(first, second)
        val videoAsset = second.assets.single { it.nodeUid == "video" }
        val imageAsset = second.assets.single { it.nodeUid == "image" }
        assertEquals(MediaKind.VIDEO, videoAsset.mediaKind)
        assertTrue(videoAsset.isFavorite)
        assertEquals(MediaKind.IMAGE, imageAsset.mediaKind)
        assertTrue(imageAsset.isFavorite)
    }

    @Test
    fun `tag index is reused for the same tag map and rebuilt for a new one`() {
        val tags = mapOf(ProtonMediaTag.SELFIES to ProtonTagState(listOf(image), hasLoaded = true))

        assertSame(memo.tagIndex(tags), memo.tagIndex(tags))
        assertNotSame(memo.tagIndex(tags), memo.tagIndex(tags.toMap()))
        assertEquals(setOf(ProtonMediaTag.SELFIES), memo.tagIndex(tags)["image"])
        assertTrue(memo.tagIndex(emptyMap()).isEmpty())
    }
}
