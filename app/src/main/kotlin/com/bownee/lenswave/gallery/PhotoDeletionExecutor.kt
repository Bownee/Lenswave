package com.bownee.lenswave.gallery

import android.content.Context
import android.net.Uri
import com.bownee.lenswave.LenswaveDispatchers
import com.bownee.lenswave.proton.ProtonPhotoGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

data class PhotoMutationResult(val successfulCount: Int, val failedCount: Int)

interface PhotoDeletionExecutor {
    suspend fun deleteDevice(uri: Uri): Int
    suspend fun trashProton(userId: UserId, nodeUids: Collection<String>): PhotoMutationResult
    suspend fun deleteProtonPermanently(userId: UserId, nodeUids: Collection<String>): PhotoMutationResult
}

@Singleton
internal class AndroidPhotoDeletionExecutor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val protonRepository: ProtonPhotoGateway,
    private val dispatchers: LenswaveDispatchers,
) : PhotoDeletionExecutor {
    override suspend fun deleteDevice(uri: Uri): Int = withContext(dispatchers.io) {
        context.contentResolver.delete(uri, null, null)
    }

    override suspend fun trashProton(
        userId: UserId,
        nodeUids: Collection<String>,
    ): PhotoMutationResult {
        val result = protonRepository.trashPhotos(userId, nodeUids)
        return PhotoMutationResult(result.trashedCount, result.failedCount)
    }

    override suspend fun deleteProtonPermanently(
        userId: UserId,
        nodeUids: Collection<String>,
    ): PhotoMutationResult {
        val result = protonRepository.deletePhotosPermanently(userId, nodeUids)
        return PhotoMutationResult(result.deletedCount, result.failedCount)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PhotoDeletionModule {
    @Binds abstract fun bindPhotoDeletionExecutor(
        implementation: AndroidPhotoDeletionExecutor,
    ): PhotoDeletionExecutor
}
