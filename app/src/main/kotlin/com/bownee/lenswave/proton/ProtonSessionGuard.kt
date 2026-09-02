package com.bownee.lenswave.proton

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId

/**
 * Owns the process-wide Proton session boundary.
 *
 * Proton Core can publish an account change while foreground or WorkManager operations are still
 * suspended. Holding this lock for the complete operation makes the transition a real barrier:
 * the old operation finishes or is cancelled before its client/cache can be torn down, and no old
 * operation can start after the new account is active.
 */
@Singleton
internal class ProtonSessionGuard @Inject constructor() {
    private val operationMutex = Mutex()
    private val activeUserId = AtomicReference<String?>(null)

    suspend fun activate(
        userId: UserId,
        transition: suspend (previousUserId: UserId?) -> Unit,
    ) = operationMutex.withLock {
        val previous = activeUserId.get()
        if (previous == userId.id) return@withLock
        activeUserId.set(null)
        withContext(NonCancellable) {
            transition(previous?.let(::UserId))
        }
        activeUserId.set(userId.id)
    }

    suspend fun <T> withActiveSession(userId: UserId, operation: suspend () -> T): T =
        operationMutex.withLock {
            if (activeUserId.get() != userId.id) {
                throw ProtonSessionChangedException()
            }
            operation()
        }

    suspend fun disconnect(
        userId: UserId,
        teardown: suspend (wasActive: Boolean) -> Unit,
    ) = operationMutex.withLock {
        val wasActive = activeUserId.get() == userId.id
        if (wasActive) activeUserId.set(null)
        withContext(NonCancellable) { teardown(wasActive) }
    }

    fun isActive(userId: UserId): Boolean = activeUserId.get() == userId.id
}

internal class ProtonSessionChangedException : CancellationException("Proton account changed")
