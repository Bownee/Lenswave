package com.bownee.lenswave.proton

import android.graphics.Bitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import me.proton.core.domain.entity.UserId
import java.io.File

interface ProtonGalleryReader {
    val state: StateFlow<ProtonGalleryState>
    val albumsState: StateFlow<ProtonAlbumsState>
    val albumPhotosState: StateFlow<ProtonAlbumPhotosState>

    suspend fun syncTimelineMetadata(
        userId: UserId,
        forceRemote: Boolean = false,
    )

    suspend fun syncTagMetadata(
        userId: UserId,
        tag: ProtonMediaTag,
        forceRemote: Boolean = false,
    )

    suspend fun syncAlbumsMetadata(
        userId: UserId,
        forceRemote: Boolean = false,
    )

    suspend fun loadCachedAlbum(
        userId: UserId,
        album: ProtonAlbumReference,
    )

    suspend fun syncAlbumPhotoMetadata(
        userId: UserId,
        album: ProtonAlbumReference,
        forceRemote: Boolean = false,
    )
}

interface ProtonSessionLifecycle {
    suspend fun activate(userId: UserId)

    suspend fun disconnect(userId: UserId)
}

/** Decoded thumbnails for grid cells and viewer placeholders. */
interface ProtonThumbnailImageSource {
    /** Null when no thumbnail is stored; the gateway re-queues it for download. */
    suspend fun loadThumbnail(
        userId: UserId,
        nodeUid: String,
    ): Bitmap?

    /**
     * The thumbnail only if it is already decoded in memory. Never touches disk or suspends, so
     * grid cells can bind it in the same frame; null means [loadThumbnail] must be called.
     */
    fun peekThumbnail(
        userId: UserId,
        nodeUid: String,
    ): Bitmap?
}

/** Full-resolution media for the viewer. */
interface ProtonOriginalMediaSource {
    suspend fun downloadOriginal(
        userId: UserId,
        nodeUid: String,
    ): File

    /** Hands [onReady] a stream that can be read while the download is still in flight. */
    suspend fun downloadOriginalProgressively(
        userId: UserId,
        nodeUid: String,
        onReady: suspend (ProtonOriginalStream) -> Unit,
    ): File

    /** Materializes an already cached original without downloading; null when not cached. */
    suspend fun prepareCachedOriginal(
        userId: UserId,
        nodeUid: String,
    ): File?

    /**
     * The stored screen-sized preview decoded for a display whose longer edge is
     * [targetLongEdge] pixels; null when none is downloaded yet. Never hits the network.
     */
    suspend fun loadPreview(
        userId: UserId,
        nodeUid: String,
        targetLongEdge: Int,
    ): Bitmap?

    suspend fun getOriginalFileName(
        userId: UserId,
        nodeUid: String,
    ): String?
}

/** Remote edits to photos; each result reports how many nodes succeeded and failed. */
interface ProtonPhotoMutations {
    suspend fun setFavorite(
        userId: UserId,
        nodeUids: Collection<String>,
        favorite: Boolean,
    ): ProtonFavoriteResult

    suspend fun trashPhotos(
        userId: UserId,
        nodeUids: Collection<String>,
    ): ProtonTrashResult
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonDataSourceModule {
    @Binds abstract fun bindProtonGalleryReader(implementation: ProtonPhotoGateway): ProtonGalleryReader

    @Binds abstract fun bindProtonSessionLifecycle(implementation: ProtonPhotoGateway): ProtonSessionLifecycle

    @Binds abstract fun bindProtonThumbnailImageSource(implementation: ProtonPhotoGateway): ProtonThumbnailImageSource

    @Binds abstract fun bindProtonOriginalMediaSource(implementation: ProtonPhotoGateway): ProtonOriginalMediaSource

    @Binds abstract fun bindProtonPhotoMutations(implementation: ProtonPhotoGateway): ProtonPhotoMutations
}
