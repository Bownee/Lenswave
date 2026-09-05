package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
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
        val changed = mapOf(ProtonMediaTag.SELFIES to ProtonTagState(listOf(image, video), hasLoaded = true))

        assertSame(memo.tagIndex(tags), memo.tagIndex(tags))
        assertNotSame(memo.tagIndex(tags), memo.tagIndex(changed))
        assertEquals(setOf(ProtonMediaTag.SELFIES), memo.tagIndex(tags)["image"])
        assertTrue(memo.tagIndex(emptyMap()).isEmpty())
    }

    @Test
    fun `a new tag map with the same tagged photos keeps the index and the mapped page`() {
        val photos = listOf(image, video)
        val tags = mapOf(ProtonMediaTag.SELFIES to ProtonTagState(listOf(image), hasLoaded = false))
        val resynced = mapOf(ProtonMediaTag.SELFIES to ProtonTagState(listOf(image.copy()), hasLoaded = true))

        val index = memo.tagIndex(tags)
        val page = memo.photos(photos, index)
        val reusedIndex = memo.tagIndex(resynced)

        assertSame(index, reusedIndex)
        assertSame(page, memo.photos(photos, reusedIndex))
        // The new map is now the remembered source, so its identity hits without a rebuild.
        assertSame(reusedIndex, memo.tagIndex(resynced))
    }

    @Test
    fun `the library page keeps its instance while the album list and account status do`() {
        val albums = listOf(album("a"))
        var builds = 0
        val build = {
            builds++
            GalleryContent.Library(listOf(LibrarySection("albums", "", albums.map(LibraryItem::Album))))
        }

        val first = memo.library(albums, ProtonAccountStatus.CONNECTED, build)

        assertSame(first, memo.library(albums, ProtonAccountStatus.CONNECTED, build))
        assertEquals(1, builds)
        assertNotSame("a new status rebuilds", first, memo.library(albums, ProtonAccountStatus.CONNECTING, build))
        assertNotSame(
            "a new list rebuilds",
            first,
            memo.library(listOf(album("a")), ProtonAccountStatus.CONNECTED, build),
        )
        assertEquals(3, builds)
    }

    @Test
    fun `switching between two photo lists and back reuses both pages`() {
        val tagIndex = memo.tagIndex(emptyMap())
        val timeline = listOf(image, video, undated)
        val filtered = listOf(video)

        val timelinePage = memo.photos(timeline, tagIndex)
        val filteredPage = memo.photos(filtered, tagIndex)

        assertSame(timelinePage, memo.photos(timeline, tagIndex))
        assertSame(filteredPage, memo.photos(filtered, tagIndex))
        assertSame(timelinePage, memo.photos(timeline, tagIndex))
        // A third list evicts the least recently used page.
        memo.photos(listOf(undated), tagIndex)
        assertSame(timelinePage, memo.photos(timeline, tagIndex))
        assertNotSame(filteredPage, memo.photos(filtered, tagIndex))
    }

    private fun album(nodeUid: String) =
        ProtonAlbum(
            nodeUid = nodeUid,
            name = "Album $nodeUid",
            photoCount = 1L,
            coverPhotoNodeUid = null,
            createdAtEpochSeconds = 0L,
            lastActivityEpochSeconds = 0L,
            hasCoverThumbnail = false,
            isShared = false,
        )
}
