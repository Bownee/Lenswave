package com.bownee.lenswave.proton

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the process-wide Proton session boundary. Operations for the active account may run
 * concurrently, while account transitions wait for all of them and prevent new work from starting.
 */
@Singleton
internal class ProtonSessionGuard
    @Inject
    constructor() {
        private val transitionMutex = Mutex()
        private val stateMutex = Mutex()
        private val activeUserId = AtomicReference<String?>(null)
        private var activeOperationCount = 0
        private var operationsFinished = completedSignal()
        private var transitionInProgress = false
        private var transitionFinished = completedSignal()

        suspend fun activate(
            userId: UserId,
            transition: suspend (previousUserId: UserId?) -> Unit,
        ) = transitionMutex.withLock {
            if (activeUserId.get() == userId.id) return@withLock
            runTransition(userId) { previous -> transition(previous) }
        }

        suspend fun <T> withActiveSession(
            userId: UserId,
            operation: suspend () -> T,
        ): T {
            while (true) {
                val transition =
                    stateMutex.withLock {
                        if (transitionInProgress) {
                            transitionFinished
                        } else {
                            if (activeUserId.get() != userId.id) throw ProtonSessionChangedException()
                            if (activeOperationCount++ == 0) operationsFinished = CompletableDeferred()
                            null
                        }
                    }
                if (transition == null) break
                transition.await()
            }
            return try {
                operation()
            } finally {
                withContext(NonCancellable) {
                    stateMutex.withLock {
                        activeOperationCount--
                        if (activeOperationCount == 0) operationsFinished.complete(Unit)
                    }
                }
            }
        }

        suspend fun disconnect(
            userId: UserId,
            teardown: suspend (wasActive: Boolean) -> Unit,
        ) = transitionMutex.withLock {
            if (activeUserId.get() != userId.id) {
                withContext(NonCancellable) { teardown(false) }
                return@withLock
            }
            runTransition(null) { teardown(true) }
        }

        fun isActive(userId: UserId): Boolean = activeUserId.get() == userId.id

        private suspend fun runTransition(
            nextUserId: UserId?,
            transition: suspend (previousUserId: UserId?) -> Unit,
        ) = withContext(NonCancellable) {
            val (previousUserId, activeOperationsFinished) =
                stateMutex.withLock {
                    transitionInProgress = true
                    transitionFinished = CompletableDeferred()
                    val previous = activeUserId.getAndSet(null)?.let(::UserId)
                    previous to operationsFinished
                }
            activeOperationsFinished.await()
            var completed = false
            try {
                transition(previousUserId)
                completed = true
            } finally {
                stateMutex.withLock {
                    activeUserId.set(nextUserId?.id.takeIf { completed })
                    transitionInProgress = false
                    transitionFinished.complete(Unit)
                }
            }
        }

        private companion object {
            fun completedSignal() = CompletableDeferred(Unit)
        }
    }

internal class ProtonSessionChangedException : CancellationException("Proton account changed")
