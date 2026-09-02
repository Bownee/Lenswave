package com.bownee.lenswave.proton

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

interface ProtonThumbnailScheduler {
    fun enqueue(userId: UserId)
    suspend fun cancelAndAwait(userId: UserId)
}

@Singleton
class ProtonThumbnailWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
) : ProtonThumbnailScheduler {
    override fun enqueue(userId: UserId) {
        workManager.enqueueUniqueWork(
            ProtonWorkNames.thumbnails(userId),
            ExistingWorkPolicy.KEEP,
            ProtonThumbnailWorker.request(userId),
        )
    }

    override suspend fun cancelAndAwait(userId: UserId) {
        withContext(Dispatchers.IO) {
            workManager.cancelUniqueWork(ProtonWorkNames.thumbnails(userId)).result.get()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonThumbnailSchedulerModule {
    @Binds abstract fun bindProtonThumbnailScheduler(
        implementation: ProtonThumbnailWorkScheduler,
    ): ProtonThumbnailScheduler
}
