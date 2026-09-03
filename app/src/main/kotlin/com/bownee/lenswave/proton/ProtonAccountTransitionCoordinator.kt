package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

fun interface ProtonAccountCacheCleaner {
    fun retainOnlyUser(userId: String?)
}

@Singleton
class ProtonAccountTransitionCoordinator
    @Inject
    constructor(
        private val sessionLifecycle: ProtonSessionLifecycle,
        private val cacheCleaner: ProtonAccountCacheCleaner,
        private val thumbnailScheduler: ProtonThumbnailScheduler,
    ) {
        suspend fun transition(
            previousUserId: UserId?,
            nextUserId: UserId?,
        ) {
            if (previousUserId == nextUserId) return

            previousUserId?.let { thumbnailScheduler.cancelAndAwait(it) }
            previousUserId?.let { sessionLifecycle.disconnect(it) }
            nextUserId?.let { sessionLifecycle.activate(it) }
            cacheCleaner.retainOnlyUser(nextUserId?.id)
            nextUserId?.let(thumbnailScheduler::enqueue)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonAccountTransitionModule {
    @Binds abstract fun bindProtonAccountCacheCleaner(implementation: ProtonPhotoCache): ProtonAccountCacheCleaner
}
