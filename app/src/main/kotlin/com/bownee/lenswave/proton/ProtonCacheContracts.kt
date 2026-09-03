package com.bownee.lenswave.proton

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

interface ProtonTimelineCache {
    fun readIndex(userId: String): List<ProtonGalleryPhoto>
    fun hasTimelineSnapshot(userId: String): Boolean
    fun writeIndex(userId: String, photos: List<ProtonGalleryPhoto>)
    fun thumbnailExists(userId: String, nodeUid: String): Boolean
    fun reconcilePhotos(
        userId: String,
        cachedNodeUids: Collection<String>,
        remoteNodeUids: Collection<String>,
    ): ProtonPhotoChanges
    fun removePhotos(userId: String, nodeUids: Collection<String>)
}

interface ProtonAlbumCache {
    fun readAlbums(userId: String): List<ProtonAlbum>
    fun hasAlbumsSnapshot(userId: String): Boolean
    fun writeAlbums(userId: String, albums: List<ProtonAlbum>)
    fun readAlbumPhotos(userId: String, albumUid: String): List<ProtonGalleryPhoto>
    fun hasAlbumPhotosSnapshot(userId: String, albumUid: String): Boolean
    fun writeAlbumPhotos(userId: String, albumUid: String, photos: List<ProtonGalleryPhoto>)
    fun reconcileAlbums(userId: String, remoteAlbumUids: Collection<String>)
    fun thumbnailExists(userId: String, nodeUid: String): Boolean
}

interface ProtonTrashCache {
    fun readTrash(userId: String): List<ProtonTrashPhoto>
    fun hasTrashSnapshot(userId: String): Boolean
    fun writeTrash(userId: String, photos: List<ProtonTrashPhoto>)
    fun thumbnailExists(userId: String, nodeUid: String): Boolean
    fun removePhotos(userId: String, nodeUids: Collection<String>)
}

interface ProtonMediaCache {
    fun readThumbnail(userId: String, nodeUid: String): ByteArray?
    fun writeThumbnail(userId: String, nodeUid: String, bytes: ByteArray)
    fun removeThumbnail(userId: String, nodeUid: String)
    fun thumbnailIsDecodable(userId: String, nodeUid: String): Boolean
    fun readOriginal(userId: String, nodeUid: String): File?
    fun createOriginalTarget(userId: String, nodeUid: String): Pair<File, File>
    fun commitOriginal(userId: String, nodeUid: String, plaintext: File, target: File): File
    fun onOriginalStored(userId: String, target: File)
}

interface ProtonSessionCache {
    fun clearUser(userId: String)
    fun trimUser(userId: String)
    fun writeLastSuccessfulSync(userId: String, source: String, timestampMillis: Long)
}

internal interface ProtonThumbnailQueueStore {
    fun readThumbnailQueue(userId: String): List<ProtonThumbnailQueueEntry>
    fun writeThumbnailQueue(userId: String, entries: List<ProtonThumbnailQueueEntry>)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonCacheModule {
    @Binds abstract fun bindTimelineCache(implementation: ProtonPhotoCache): ProtonTimelineCache
    @Binds abstract fun bindAlbumCache(implementation: ProtonPhotoCache): ProtonAlbumCache
    @Binds abstract fun bindTrashCache(implementation: ProtonPhotoCache): ProtonTrashCache
    @Binds abstract fun bindMediaCache(implementation: ProtonPhotoCache): ProtonMediaCache
    @Binds abstract fun bindSessionCache(implementation: ProtonPhotoCache): ProtonSessionCache
    @Binds abstract fun bindThumbnailQueueStore(
        implementation: ProtonPhotoCache,
    ): ProtonThumbnailQueueStore
}
