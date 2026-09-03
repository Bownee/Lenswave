package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonPhotoGateway
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonTrashState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import me.proton.core.domain.entity.UserId

interface DevicePhotoSource {
    suspend fun loadPhotos(): List<GalleryAsset>
    suspend fun loadTrashedPhotos(): List<GalleryAsset>
}

interface ProtonGalleryReader {
    val state: StateFlow<ProtonGalleryState>
    val albumsState: StateFlow<ProtonAlbumsState>
    val albumPhotosState: StateFlow<ProtonAlbumPhotosState>
    val trashState: StateFlow<ProtonTrashState>

    suspend fun syncTimelineMetadata(userId: UserId, forceRemote: Boolean = false)
    suspend fun syncTagMetadata(userId: UserId, tag: ProtonMediaTag, forceRemote: Boolean = false)
    suspend fun syncAlbumsMetadata(userId: UserId, forceRemote: Boolean = false)
    suspend fun syncTrashMetadata(userId: UserId, forceRemote: Boolean = false)
    suspend fun loadCachedAlbum(userId: UserId, album: ProtonAlbumReference)
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

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GalleryDataModule {
    @Binds abstract fun bindDevicePhotoSource(implementation: DevicePhotoRepository): DevicePhotoSource
    @Binds abstract fun bindGalleryNavigationStore(
        implementation: SharedPreferencesGalleryNavigationStore,
    ): GalleryNavigationStore
    @Binds abstract fun bindProtonGalleryReader(implementation: ProtonPhotoGateway): ProtonGalleryReader
    @Binds abstract fun bindProtonSessionLifecycle(implementation: ProtonPhotoGateway): ProtonSessionLifecycle
}
