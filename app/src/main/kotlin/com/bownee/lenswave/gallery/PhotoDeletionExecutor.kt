package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonPhotoMutations
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

data class PhotoMutationResult(
    val successfulCount: Int,
    val failedCount: Int,
)

interface PhotoDeletionExecutor {
    suspend fun trashProton(
        userId: UserId,
        nodeUids: Collection<String>,
    ): PhotoMutationResult
}

@Singleton
internal class ProtonPhotoDeletionExecutor
    @Inject
    constructor(
        private val protonRepository: ProtonPhotoMutations,
    ) : PhotoDeletionExecutor {
        override suspend fun trashProton(
            userId: UserId,
            nodeUids: Collection<String>,
        ): PhotoMutationResult {
            val result = protonRepository.trashPhotos(userId, nodeUids)
            return PhotoMutationResult(result.trashedCount, result.failedCount)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PhotoDeletionModule {
    @Binds abstract fun bindPhotoDeletionExecutor(implementation: ProtonPhotoDeletionExecutor): PhotoDeletionExecutor
}
