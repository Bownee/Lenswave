package com.bownee.lenswave.proton

import android.graphics.Bitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

interface ProtonTimelineCache {
    fun readIndex(userId: String): List<ProtonGalleryPhoto>

    fun hasTimelineSnapshot(userId: String): Boolean

    fun writeIndex(
        userId: String,
        photos: List<ProtonGalleryPhoto>,
    )

    fun readTag(
        userId: String,
        tag: ProtonMediaTag,
    ): List<ProtonGalleryPhoto>

    fun hasTagSnapshot(
        userId: String,
        tag: ProtonMediaTag,
    ): Boolean

    fun writeTag(
        userId: String,
        tag: ProtonMediaTag,
        photos: List<ProtonGalleryPhoto>,
    )

    fun thumbnailExists(
        userId: String,
        nodeUid: String,
    ): Boolean

    fun previewExists(
        userId: String,
        nodeUid: String,
    ): Boolean

    /** Drops cached media and tag entries for nodes that vanished from the remote timeline. */
    fun reconcilePhotos(
        userId: String,
        cachedNodeUids: Collection<String>,
        remoteNodeUids: Collection<String>,
    )

    fun removePhotos(
        userId: String,
        nodeUids: Collection<String>,
    )
}

interface ProtonAlbumCache {
    fun readAlbums(userId: String): List<ProtonAlbum>

    fun hasAlbumsSnapshot(userId: String): Boolean

    fun writeAlbums(
        userId: String,
        albums: List<ProtonAlbum>,
    )

    fun readAlbumPhotos(
        userId: String,
        albumUid: String,
    ): List<ProtonGalleryPhoto>

    fun hasAlbumPhotosSnapshot(
        userId: String,
        albumUid: String,
    ): Boolean

    fun writeAlbumPhotos(
        userId: String,
        albumUid: String,
        photos: List<ProtonGalleryPhoto>,
    )

    fun reconcileAlbums(
        userId: String,
        remoteAlbumUids: Collection<String>,
    )

    fun thumbnailExists(
        userId: String,
        nodeUid: String,
    ): Boolean
}

interface ProtonMediaCache {
    fun loadThumbnail(
        userId: String,
        nodeUid: String,
    ): Bitmap?

    /** In-memory thumbnail only; never reads disk, so it is safe on the main thread. */
    fun peekThumbnail(
        userId: String,
        nodeUid: String,
    ): Bitmap?

    fun writeThumbnail(
        userId: String,
        nodeUid: String,
        bytes: ByteArray,
    )

    fun removeThumbnail(
        userId: String,
        nodeUid: String,
    )

    fun thumbnailCount(userId: String): Int

    /** The stored thumbnail exactly as Proton delivered it; null when absent or unreadable. */
    fun readThumbnailBytes(
        userId: String,
        nodeUid: String,
    ): ByteArray?

    fun previewExists(
        userId: String,
        nodeUid: String,
    ): Boolean

    /** Stores a preview exactly as delivered; throws when the bytes are not a decodable image. */
    fun writePreview(
        userId: String,
        nodeUid: String,
        bytes: ByteArray,
    )

    /** Decodes the stored preview so its longer edge covers [targetLongEdge] pixels; null when absent. */
    fun loadPreview(
        userId: String,
        nodeUid: String,
        targetLongEdge: Int,
    ): Bitmap?

    fun removePreview(
        userId: String,
        nodeUid: String,
    )

    fun previewCount(userId: String): Int

    fun readOriginal(
        userId: String,
        nodeUid: String,
    ): File?

    fun createOriginalTarget(
        userId: String,
        nodeUid: String,
    ): Pair<File, File>

    fun commitOriginal(
        userId: String,
        nodeUid: String,
        plaintext: File,
        target: File,
    ): File

    fun onOriginalStored(
        userId: String,
        target: File,
    )
}

interface ProtonSessionCache {
    fun clearUser(userId: String)

    fun trimUser(userId: String)
}

/** Persistence for the download queues; each [ProtonQueueName] maps to its own file per user. */
internal interface ProtonThumbnailQueueStore {
    fun readQueue(
        userId: String,
        queue: ProtonQueueName,
    ): List<ProtonThumbnailQueueEntry>

    fun writeQueue(
        userId: String,
        queue: ProtonQueueName,
        entries: List<ProtonThumbnailQueueEntry>,
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonCacheModule {
    @Binds abstract fun bindTimelineCache(implementation: ProtonPhotoCache): ProtonTimelineCache

    @Binds abstract fun bindAlbumCache(implementation: ProtonPhotoCache): ProtonAlbumCache

    @Binds abstract fun bindMediaCache(implementation: ProtonPhotoCache): ProtonMediaCache

    @Binds abstract fun bindSessionCache(implementation: ProtonPhotoCache): ProtonSessionCache

    @Binds abstract fun bindThumbnailQueueStore(implementation: ProtonPhotoCache): ProtonThumbnailQueueStore
}
