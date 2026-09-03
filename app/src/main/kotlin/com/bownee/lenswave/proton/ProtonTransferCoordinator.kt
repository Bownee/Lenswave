package com.bownee.lenswave.proton

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps background transfers from starting while media the user explicitly opened is still
 * downloading. This is an admission check, not a lock: a background transfer that already began
 * keeps running when a foreground one starts, and a foreground transfer never waits.
 */
@Singleton
internal class ProtonTransferCoordinator
    @Inject
    constructor() {
        private val foregroundCount = MutableStateFlow(0)

        suspend fun <T> withForegroundTransfer(operation: suspend () -> T): T {
            foregroundCount.update { count -> count + 1 }
            return try {
                operation()
            } finally {
                withContext(NonCancellable) { foregroundCount.update { count -> count - 1 } }
            }
        }

        suspend fun <T> withBackgroundTransfer(operation: suspend () -> T): T {
            foregroundCount.first { count -> count == 0 }
            return operation()
        }
    }
