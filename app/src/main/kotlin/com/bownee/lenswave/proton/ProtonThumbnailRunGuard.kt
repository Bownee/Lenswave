package com.bownee.lenswave.proton

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admits one [ProtonThumbnailWorker] batch loop per process. Unique work de-duplicates only
 * within one name and only for the requests WorkManager knows about; this guard is the
 * process-wide backstop that makes the queues' single-claimer assumption hold (see
 * [ProtonBackgroundBatchPolicy.hasStaleClaims]): a run that is not admitted ends at once and
 * leaves every claim, and every release of a stale claim, to the run that is.
 */
@Singleton
internal class ProtonThumbnailRunGuard
    @Inject
    constructor() {
        private val active = AtomicBoolean(false)

        /** True when the caller is now the active run and must call [end] when done. */
        fun tryBegin(): Boolean = active.compareAndSet(false, true)

        fun end() {
            active.set(false)
        }

        val isActive: Boolean get() = active.get()
    }
