package com.bownee.lenswave.proton

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId

/** The slice of [ProtonPhotoGateway] that the background thumbnail worker drives. */
internal interface ProtonThumbnailWorkGateway {
    /** [allowPreviews] false serves thumbnails only and reports deferred previews through the idle step. */
    suspend fun downloadNextQueuedThumbnailBatch(
        userId: UserId,
        allowPreviews: Boolean,
        onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
    ): ProtonThumbnailQueueStep

    suspend fun thumbnailWorkProgress(userId: UserId): ProtonThumbnailWorkProgress
}

@Module
@InstallIn(SingletonComponent::class)
internal object ProtonThumbnailWorkModule {
    @Provides
    fun provideThumbnailWorkGateway(gateway: ProtonPhotoGateway): ProtonThumbnailWorkGateway = gateway.thumbnailWork
}
