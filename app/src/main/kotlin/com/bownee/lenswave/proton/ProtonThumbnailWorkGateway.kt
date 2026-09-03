package com.bownee.lenswave.proton

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId

/** The slice of [ProtonPhotoGateway] that the background thumbnail worker drives. */
internal interface ProtonThumbnailWorkGateway {
    suspend fun downloadNextQueuedThumbnailBatch(
        userId: UserId,
        onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
    ): ProtonThumbnailQueueStep

    suspend fun thumbnailWorkProgress(userId: UserId): ProtonThumbnailWorkProgress

    fun updateThumbnailWorkStatus(status: ProtonThumbnailWorkStatus?)
}

@Module
@InstallIn(SingletonComponent::class)
internal object ProtonThumbnailWorkModule {
    @Provides
    fun provideThumbnailWorkGateway(gateway: ProtonPhotoGateway): ProtonThumbnailWorkGateway =
        gateway.thumbnailWork
}
