package com.bownee.lenswave.proton

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Prevents new background transfers from competing with media explicitly opened by the user. */
@Singleton
internal class ProtonTransferCoordinator @Inject constructor() {
    private val mutex = Mutex()
    private val foregroundCount = MutableStateFlow(0)

    suspend fun <T> withForegroundTransfer(operation: suspend () -> T): T {
        mutex.withLock { foregroundCount.value++ }
        return try {
            operation()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { foregroundCount.value-- }
            }
        }
    }

    suspend fun <T> withBackgroundTransfer(operation: suspend () -> T): T {
        while (true) {
            foregroundCount.first { count -> count == 0 }
            val acquired = mutex.withLock { foregroundCount.value == 0 }
            if (acquired) return operation()
        }
    }
}
