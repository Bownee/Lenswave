package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSyncKeysTest {
    @Test
    fun albumPhotoSyncKeysAreScopedToTheAlbum() {
        assertEquals("album-photos:album-1", ProtonSyncKeys.albumPhotos("album-1"))
    }

    @Test
    fun tagSyncKeysUseTheLowercaseTagName() {
        assertEquals("timeline-tag:favorites", ProtonSyncKeys.timelineTag(ProtonMediaTag.FAVORITES))
        assertEquals("timeline-tag:live_photos", ProtonSyncKeys.timelineTag(ProtonMediaTag.LIVE_PHOTOS))
    }

    @Test
    fun albumPhotoQueueSourcesAreRecognisedByTheirPrefix() {
        val source = ProtonSyncKeys.QueueSource.albumPhotos("album-1")

        assertEquals("album:album-1", source)
        assertTrue(ProtonSyncKeys.QueueSource.isAlbumPhotos(source))
        assertFalse(ProtonSyncKeys.QueueSource.isAlbumPhotos(ProtonSyncKeys.QueueSource.ALBUM_COVERS))
        assertFalse(ProtonSyncKeys.QueueSource.isAlbumPhotos(ProtonSyncKeys.QueueSource.TIMELINE))
    }

    @Test
    fun persistedNamesAreUnchanged() {
        // Renaming any of these would discard sync stamps and queued work already stored on devices.
        assertEquals("timeline", ProtonSyncKeys.TIMELINE)
        assertEquals("timeline-tag", ProtonSyncKeys.TIMELINE_TAG)
        assertEquals("albums", ProtonSyncKeys.ALBUMS)
        assertEquals("album-photos", ProtonSyncKeys.ALBUM_PHOTOS)
        assertEquals("trash", ProtonSyncKeys.TRASH)
        assertEquals("timeline", ProtonSyncKeys.QueueSource.TIMELINE)
        assertEquals("album-covers", ProtonSyncKeys.QueueSource.ALBUM_COVERS)
        assertEquals("trash", ProtonSyncKeys.QueueSource.TRASH)
        assertEquals("album", ProtonSyncKeys.QueueSource.ALBUM_PHOTOS)
    }
}
