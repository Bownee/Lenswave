package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonPhotoGateway
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
    suspend fun calculateSha1(photo: GalleryAsset): ByteArray
}

interface CombinedPhotoMatcher {
    suspend fun resolveMatches(
        userId: UserId,
        devicePhotos: List<GalleryAsset>,
        protonPhotos: List<ProtonGalleryPhoto>,
        forceRecheck: Boolean = false,
        onProgress: suspend (CombinedMatchProgress) -> Unit,
    )

    suspend fun clear(userId: UserId)
}

interface CombinedMatchStore {
    fun read(userId: String): CombinedMatchSnapshot
    fun write(userId: String, snapshot: CombinedMatchSnapshot)
    fun append(
        userId: String,
        timelineFingerprint: String,
        records: Collection<DevicePhotoMatchRecord>,
    )
    fun clear(userId: String)
}

interface ProtonDuplicateSource {
    suspend fun getOriginalFileName(userId: UserId, nodeUid: String): String?
    suspend fun findPhotoDuplicates(
        userId: UserId,
        name: String,
        generateSha1: suspend () -> ByteArray,
    ): List<String>
}

interface ProtonGalleryReader {
    val state: StateFlow<ProtonGalleryState>
    val albumsState: StateFlow<ProtonAlbumsState>
    val albumPhotosState: StateFlow<ProtonAlbumPhotosState>
    val trashState: StateFlow<ProtonTrashState>

    suspend fun syncThumbnails(userId: UserId, forceRemote: Boolean = false, maxThumbnailDownloads: Int? = null)
    suspend fun syncAlbums(userId: UserId, forceRemote: Boolean = false, maxThumbnailDownloads: Int? = null)
    suspend fun loadCachedAlbum(userId: UserId, album: ProtonAlbumReference)
    suspend fun syncAlbumPhotos(userId: UserId, album: ProtonAlbumReference, forceRemote: Boolean = false)
    suspend fun syncTrash(userId: UserId, forceRemote: Boolean = false)
}

interface ProtonSessionLifecycle {
    suspend fun activate(userId: UserId)
    suspend fun disconnect(userId: UserId)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GalleryDataModule {
    @Binds abstract fun bindDevicePhotoSource(implementation: DevicePhotoRepository): DevicePhotoSource
    @Binds abstract fun bindCombinedPhotoMatcher(implementation: CombinedPhotoRepository): CombinedPhotoMatcher
    @Binds abstract fun bindCombinedMatchStore(implementation: CombinedPhotoCache): CombinedMatchStore
    @Binds abstract fun bindGalleryNavigationStore(
        implementation: SharedPreferencesGalleryNavigationStore,
    ): GalleryNavigationStore
    @Binds abstract fun bindProtonGalleryReader(implementation: ProtonPhotoGateway): ProtonGalleryReader
    @Binds abstract fun bindProtonSessionLifecycle(implementation: ProtonPhotoGateway): ProtonSessionLifecycle
    @Binds abstract fun bindProtonDuplicateSource(implementation: ProtonPhotoGateway): ProtonDuplicateSource
}
