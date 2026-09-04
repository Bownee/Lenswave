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
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * Every run of one user shares one unique work name, so WorkManager never has two of them
 * runnable at once. Requests from the app use KEEP: a run that is queued or going is the run
 * they want. Requests the worker makes about itself (the charging follow-up) use
 * APPEND_OR_REPLACE, the one policy that neither drops the request because the current run is
 * still going (KEEP would) nor cancels that run (REPLACE would): the follow-up is chained after
 * it, and a chain whose head was cancelled is replaced instead of extended.
 */
@Singleton
class ProtonThumbnailWorkScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : ProtonThumbnailScheduler {
        private val legacyWorkCancelled = AtomicBoolean(false)

        override fun enqueue(userId: UserId) {
            cancelLegacyWork(userId)
            workManager.enqueueUniqueWork(
                ProtonWorkNames.thumbnails(userId),
                ExistingWorkPolicy.KEEP,
                ProtonThumbnailWorker.request(userId),
            )
        }

        override fun enqueueWhileCharging(userId: UserId) {
            cancelLegacyWork(userId)
            workManager.enqueueUniqueWork(
                ProtonWorkNames.thumbnails(userId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
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
                workManager.cancelUniqueWork(ProtonWorkNames.legacyThumbnailsWhileCharging(userId)).result.get()
                workManager.cancelUniqueWork(ProtonWorkNames.thumbnails(userId)).result.get()
            }
        }

        /**
         * Earlier versions queued the charging run under its own name. Whatever an upgrade left
         * there would run beside the single name, so it is cancelled once per process; the
         * cancel is a no-op when nothing is queued.
         */
        private fun cancelLegacyWork(userId: UserId) {
            if (legacyWorkCancelled.compareAndSet(false, true)) {
                workManager.cancelUniqueWork(ProtonWorkNames.legacyThumbnailsWhileCharging(userId))
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
