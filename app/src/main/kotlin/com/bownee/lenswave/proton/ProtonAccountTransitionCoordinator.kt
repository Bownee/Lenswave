package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import com.bownee.lenswave.viewer.ViewerMutationCoordinator
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

/** Drops queued and in-flight photo mutation outcomes; they belong to the account that is going. */
fun interface ProtonAccountMutationForgetter {
    fun forgetAll()
}

@Singleton
class ProtonAccountTransitionCoordinator
    @Inject
    constructor(
        private val sessionLifecycle: ProtonSessionLifecycle,
        private val cacheCleaner: ProtonAccountCacheCleaner,
        private val thumbnailScheduler: ProtonThumbnailScheduler,
        private val mutationForgetter: ProtonAccountMutationForgetter,
    ) {
        private val swept = AtomicBoolean()

        /**
         * A transition between equal accounts is a no-op, except for the first one the process
         * sees: a sign-out that could not delete everything leaves residue behind, and with no
         * account signed in the next launch never transitions, so that first observation is
         * where the orphaned caches get swept.
         *
         * The sweep keeps only [nextUserId]'s caches, so it must know that nobody else's are
         * wanted. A [nextUserId] of null with [accountAbsent] false is an account that exists but
         * has not finished loading: its caches are about to be needed, and sweeping them (every
         * rendition, original and queue of the only account there is) because it was slow to
         * become ready is the one thing this must never do. That observation is skipped and the
         * sweep waits for the account to become ready or to be removed. [swept] is only set once
         * the cleaner has returned, so a sweep that threw is retried by the session manager.
         */
        suspend fun transition(
            previousUserId: UserId?,
            nextUserId: UserId?,
            accountAbsent: Boolean,
        ) {
            val retainedUserId = nextUserId?.id
            if (previousUserId == nextUserId) {
                if (retainedUserId == null && !accountAbsent) return
                if (!swept.get()) {
                    cacheCleaner.retainOnlyUser(retainedUserId)
                    swept.set(true)
                }
                return
            }

            previousUserId?.let { thumbnailScheduler.cancelAndAwait(it) }
            previousUserId?.let { sessionLifecycle.disconnect(it) }
            // After the disconnect: a mutation still running ends with a session change and
            // would otherwise re-queue a failed outcome for a viewer of the next account.
            mutationForgetter.forgetAll()
            nextUserId?.let { sessionLifecycle.activate(it) }
            // No ready account and none absent: the account is between states, and nothing says
            // whose caches are wanted, so the sweep waits (see above).
            if (retainedUserId != null || accountAbsent) {
                cacheCleaner.retainOnlyUser(retainedUserId)
                swept.set(true)
            }
            nextUserId?.let(thumbnailScheduler::enqueue)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonAccountTransitionModule {
    @Binds abstract fun bindProtonAccountCacheCleaner(implementation: ProtonPhotoCache): ProtonAccountCacheCleaner

    @Binds
    abstract fun bindProtonAccountMutationForgetter(
        implementation: ViewerMutationCoordinator,
    ): ProtonAccountMutationForgetter
}
