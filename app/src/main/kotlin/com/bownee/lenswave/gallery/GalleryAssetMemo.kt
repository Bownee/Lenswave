package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTagState
import java.util.EnumSet

/**
 * Remembers the last tag index and the last two mapped-and-sorted photo pages by the identity of
 * the lists they were built from.
 *
 * The repository state flows carry the same list instances across emissions that only change a
 * flag (syncing, refreshFailed, ...), and the view model publishes for local changes such as
 * isRefreshing. Reusing the previous result for those makes every such publish O(1) instead of an
 * O(n log n) map-and-sort of the whole timeline; it also keeps the returned content instance
 * stable so consumers can compare it by reference.
 *
 * A tag map that arrives as a new instance with the same photos per tag (a tag sync that found
 * nothing new) keeps the previous index instance, so it does not force a re-map either. Two page
 * slots let the user switch between the timeline and a filter or album and back without paying
 * for the timeline twice.
 *
 * Safe to call from any thread: each cache slot holds one immutable entry.
 */
internal class GalleryAssetMemo {
    @Volatile private var tagIndexEntry: TagIndexEntry? = null

    @Volatile private var assetsEntry: AssetsEntry? = null

    @Volatile private var previousAssetsEntry: AssetsEntry? = null

    fun tagIndex(tags: Map<ProtonMediaTag, ProtonTagState>): Map<String, Set<ProtonMediaTag>> {
        val entry = tagIndexEntry
        if (entry != null && entry.source === tags) return entry.index
        // Rebuilding costs one pass over the tagged photos; comparing the maps would cost the same,
        // and comparing the resulting indexes keeps the instance whenever the content is unchanged.
        val built = buildTagIndex(tags)
        val index = if (entry != null && entry.index == built) entry.index else built
        tagIndexEntry = TagIndexEntry(tags, index)
        return index
    }

    /** The photos mapped to assets and ordered newest first; the same instance while both inputs are. */
    fun photos(
        photos: List<ProtonGalleryPhoto>,
        tagIndex: Map<String, Set<ProtonMediaTag>>,
    ): GalleryContent.Photos {
        val current = assetsEntry
        if (current != null && current.matches(photos, tagIndex)) return current.content
        val previous = previousAssetsEntry
        if (previous != null && previous.matches(photos, tagIndex)) {
            previousAssetsEntry = current
            assetsEntry = previous
            return previous.content
        }
        val content = GalleryContent.Photos(GalleryGrouping.sortPhotos(photos.map { it.toGalleryAsset(tagIndex) }))
        previousAssetsEntry = current
        assetsEntry = AssetsEntry(photos, tagIndex, content)
        return content
    }

    private class TagIndexEntry(
        val source: Map<ProtonMediaTag, ProtonTagState>,
        val index: Map<String, Set<ProtonMediaTag>>,
    )

    private class AssetsEntry(
        val photos: List<ProtonGalleryPhoto>,
        val tagIndex: Map<String, Set<ProtonMediaTag>>,
        val content: GalleryContent.Photos,
    ) {
        fun matches(
            photos: List<ProtonGalleryPhoto>,
            tagIndex: Map<String, Set<ProtonMediaTag>>,
        ) = this.photos === photos && this.tagIndex === tagIndex
    }

    private companion object {
        fun buildTagIndex(tags: Map<ProtonMediaTag, ProtonTagState>): Map<String, Set<ProtonMediaTag>> {
            if (tags.isEmpty()) return emptyMap()
            val index = HashMap<String, EnumSet<ProtonMediaTag>>()
            tags.forEach { (tag, state) ->
                state.photos.forEach { photo ->
                    index.getOrPut(photo.nodeUid) { EnumSet.noneOf(ProtonMediaTag::class.java) }.add(tag)
                }
            }
            return index
        }

        fun ProtonGalleryPhoto.toGalleryAsset(tagIndex: Map<String, Set<ProtonMediaTag>>): GalleryAsset {
            val tags = tagIndex[nodeUid].orEmpty()
            return toGalleryAsset(
                mediaKind = if (ProtonMediaTag.VIDEOS in tags) MediaKind.VIDEO else MediaKind.IMAGE,
                tags = tags,
            )
        }
    }
}
