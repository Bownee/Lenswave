package com.bownee.lenswave.proton

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

interface ProtonThumbnailScheduler {
    fun enqueue(userId: UserId)

    /** Queues a run that starts once the phone is charging, for the previews a normal run deferred. */
    fun enqueueWhileCharging(userId: UserId)

    suspend fun resume(userId: UserId)

    suspend fun restart(userId: UserId)

    suspend fun cancelAndAwait(userId: UserId)
}

@Singleton
class ProtonThumbnailWorkScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : ProtonThumbnailScheduler {
        override fun enqueue(userId: UserId) {
            workManager.enqueueUniqueWork(
                ProtonWorkNames.thumbnails(userId),
                ExistingWorkPolicy.KEEP,
                ProtonThumbnailWorker.request(userId),
            )
        }

        override fun enqueueWhileCharging(userId: UserId) {
            workManager.enqueueUniqueWork(
                ProtonWorkNames.thumbnailsWhileCharging(userId),
                ExistingWorkPolicy.KEEP,
                ProtonThumbnailWorker.request(userId, requiresCharging = true),
            )
        }

        override suspend fun resume(userId: UserId) {
            withContext(Dispatchers.IO) {
                val workName = ProtonWorkNames.thumbnails(userId)
                val states = workManager.getWorkInfosForUniqueWork(workName).get().map { work -> work.state }
                // A run that is still going keeps its progress; anything else is replaced.
                if (WorkInfo.State.RUNNING in states) return@withContext
                replace(workName, userId)
            }
        }

        override suspend fun restart(userId: UserId) {
            withContext(Dispatchers.IO) {
                replace(ProtonWorkNames.thumbnails(userId), userId)
            }
        }

        override suspend fun cancelAndAwait(userId: UserId) {
            withContext(Dispatchers.IO) {
                workManager.cancelUniqueWork(ProtonWorkNames.thumbnailsWhileCharging(userId)).result.get()
                workManager.cancelUniqueWork(ProtonWorkNames.thumbnails(userId)).result.get()
            }
        }

        private fun replace(
            workName: String,
            userId: UserId,
        ) {
            workManager
                .enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.REPLACE,
                    ProtonThumbnailWorker.request(userId),
                ).result
                .get()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonThumbnailSchedulerModule {
    @Binds
    abstract fun bindProtonThumbnailScheduler(implementation: ProtonThumbnailWorkScheduler): ProtonThumbnailScheduler
}
