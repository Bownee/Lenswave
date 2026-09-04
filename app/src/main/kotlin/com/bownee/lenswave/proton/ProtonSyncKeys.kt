package com.bownee.lenswave.proton

/**
 * Names that identify persisted Proton sync state. Sync keys stamp the last successful
 * enumeration of one listing; queue sources tag which listing asked for a thumbnail.
 */
internal object ProtonSyncKeys {
    const val TIMELINE = "timeline"
    const val TIMELINE_TAG = "timeline-tag"
    const val ALBUMS = "albums"
    const val ALBUM_PHOTOS = "album-photos"

    fun timelineTag(tag: ProtonMediaTag): String = "$TIMELINE_TAG:${tag.name.lowercase()}"

    fun albumPhotos(albumUid: String): String = "$ALBUM_PHOTOS:$albumUid"

    /** Thumbnail queue source names; one entry may be requested by several sources at once. */
    object QueueSource {
        const val TIMELINE = "timeline"
        const val ALBUM_COVERS = "album-covers"

        /** The only source of the preview queue: screen-sized renditions of the timeline. */
        const val TIMELINE_PREVIEWS = "timeline-previews"

        /** Prefix for the photos of one album; the album node uid follows the colon. */
        const val ALBUM_PHOTOS = "album"

        fun albumPhotos(albumUid: String): String = "$ALBUM_PHOTOS:$albumUid"

        fun isAlbumPhotos(source: String): Boolean = source.startsWith("$ALBUM_PHOTOS:")
    }
}
