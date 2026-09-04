package com.bownee.lenswave.proton

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps background transfers from starting while media the user explicitly opened is still
 * downloading. This is an admission check, not a lock: a background transfer that already began
 * keeps running when a foreground one starts, and a foreground transfer never waits.
 *
 * The background side never waits for long either. The worker asks [awaitNoForegroundTransfer]
 * before it claims a batch and, told no, ends or sleeps on its own terms; a pass that is already
 * inside [withBackgroundTransfer] waits a moment and then goes ahead, because an unbounded wait
 * there sat outside every pass deadline and kept the foreground service, its wakelock and the
 * claimed batch alive for as long as the viewer took to fetch an original.
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

        /** True once no foreground transfer is going, false when one still is after [timeoutMillis]. */
        suspend fun awaitNoForegroundTransfer(timeoutMillis: Long): Boolean {
            if (foregroundCount.value == 0) return true
            return withTimeoutOrNull(timeoutMillis) { foregroundCount.first { count -> count == 0 } } != null
        }

        suspend fun <T> withBackgroundTransfer(
            maxWaitMillis: Long = MAX_BACKGROUND_WAIT_MILLIS,
            operation: suspend () -> T,
        ): T {
            awaitNoForegroundTransfer(maxWaitMillis)
            return operation()
        }

        companion object {
            /** A pass already under way shares the network after this rather than holding its claims. */
            const val MAX_BACKGROUND_WAIT_MILLIS = 5_000L
        }
    }
