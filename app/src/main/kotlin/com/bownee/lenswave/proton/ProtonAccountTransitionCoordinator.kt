package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId
import java.util.concurrent.atomic.AtomicBoolean
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
        private val swept = AtomicBoolean()

        /**
         * A transition between equal accounts is a no-op, except for the first one the process
         * sees: a sign-out that could not delete everything leaves residue behind, and with no
         * account signed in the next launch never transitions, so that first observation is
         * where the orphaned caches get swept.
         */
        suspend fun transition(
            previousUserId: UserId?,
            nextUserId: UserId?,
        ) {
            if (previousUserId == nextUserId) {
                if (swept.compareAndSet(false, true)) cacheCleaner.retainOnlyUser(nextUserId?.id)
                return
            }

            previousUserId?.let { thumbnailScheduler.cancelAndAwait(it) }
            previousUserId?.let { sessionLifecycle.disconnect(it) }
            nextUserId?.let { sessionLifecycle.activate(it) }
            cacheCleaner.retainOnlyUser(nextUserId?.id)
            swept.set(true)
            nextUserId?.let(thumbnailScheduler::enqueue)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonAccountTransitionModule {
    @Binds abstract fun bindProtonAccountCacheCleaner(implementation: ProtonPhotoCache): ProtonAccountCacheCleaner
}
