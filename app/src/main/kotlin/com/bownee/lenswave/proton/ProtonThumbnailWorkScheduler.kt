package com.bownee.lenswave.proton

import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

interface ProtonThumbnailScheduler {
    fun enqueue(userId: UserId)

    /** Queues a run that starts once the phone is charging, for the previews a normal run deferred. */
    fun enqueueWhileCharging(userId: UserId)

    /** The app is back on screen: the same as [enqueue], never a cancel of a run in progress. */
    suspend fun resume(userId: UserId)

    /** The only request that cancels a run in progress; for a listing that changed underneath it. */
    suspend fun restart(userId: UserId)

    suspend fun cancelAndAwait(userId: UserId)

    /**
     * Lifts the pause the user set from the worker's notification, so that the enqueue a manual
     * refresh makes right after goes through. Schedulers without a pause (test doubles) ignore it.
     */
    fun clearPaused(userId: UserId) {}
}

/** The requests a running worker makes about itself; see [ProtonThumbnailFollowUpPolicy]. */
internal interface ProtonThumbnailFollowUpScheduler {
    fun enqueueFollowUp(
        userId: UserId,
        followUp: ProtonThumbnailFollowUp,
    )
}

/**
 * Every run of one user shares one unique work name, so WorkManager never has two of them
 * runnable at once. Requests from the app use KEEP: a run that is queued or going is the run
 * they want. Requests the worker makes about itself (the follow-ups) use APPEND_OR_REPLACE, the
 * one policy that neither drops the request because the current run is still going (KEEP
 * would) nor cancels that run (REPLACE would): the follow-up is chained after it, and a chain
 * whose head was cancelled is replaced instead of extended.
 *
 * The app asks for a run on every refresh, tab switch, album and resume. Each ask used to be a
 * WorkManager transaction, and a resume replaced whatever was there, cancelling a batch in
 * progress. Now the scheduler watches the unique work and answers from memory: an ask while a
 * run is queued or going is nothing, and asks are otherwise spaced by
 * [ProtonThumbnailEnqueuePolicy.DEBOUNCE_MILLIS].
 *
 * One queued request is not the run the app wants: the charging follow-up a run left for the
 * previews it deferred. It waits for the charger, but previews are also allowed while the app
 * is on screen, and every ask from the app is made on screen. So an ask that finds only that
 * request waiting replaces it with a plain one, which runs now and leaves its own charging
 * follow-up for whatever it defers. A follow-up that is already running is a run in progress
 * and is kept like any other. Otherwise only [restart] replaces.
 */
@Singleton
internal class ProtonThumbnailWorkScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
        private val pauseStore: ProtonThumbnailPauseStore,
    ) : ProtonThumbnailScheduler,
        ProtonThumbnailFollowUpScheduler {
        private val legacyWorkCancelled = AtomicBoolean(false)
        private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val observed = ConcurrentHashMap<String, Observation>()

        override fun enqueue(userId: UserId) {
            if (pauseStore.isPaused(userId)) return
            cancelLegacyWork(userId)
            val workName = ProtonWorkNames.thumbnails(userId)
            val observation = observe(workName)
            val now = SystemClock.elapsedRealtime()
            val waitingForCharger = observation.waitingForCharger
            if (!ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                    observation.active,
                    observation.lastEnqueuedAtMillis,
                    now,
                    waitingForCharger,
                )
            ) {
                return
            }
            observation.lastEnqueuedAtMillis = now
            workManager.enqueueUniqueWork(
                workName,
                ProtonThumbnailEnqueuePolicy.existingWorkPolicy(waitingForCharger),
                ProtonThumbnailWorker.request(userId),
            )
        }

        override fun enqueueWhileCharging(userId: UserId) {
            enqueueFollowUp(userId, ProtonThumbnailFollowUp(requiresCharging = true))
        }

        override fun enqueueFollowUp(
            userId: UserId,
            followUp: ProtonThumbnailFollowUp,
        ) {
            if (pauseStore.isPaused(userId)) return
            cancelLegacyWork(userId)
            workManager.enqueueUniqueWork(
                ProtonWorkNames.thumbnails(userId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                ProtonThumbnailWorker.request(
                    userId,
                    requiresCharging = followUp.requiresCharging,
                    initialDelayMillis = followUp.initialDelayMillis,
                    networkWaitAttempt = followUp.networkWaitAttempt,
                ),
            )
        }

        override suspend fun resume(userId: UserId) {
            enqueue(userId)
        }

        override suspend fun restart(userId: UserId) {
            withContext(Dispatchers.IO) {
                if (pauseStore.isPaused(userId)) return@withContext
                val workName = ProtonWorkNames.thumbnails(userId)
                observe(workName).lastEnqueuedAtMillis = SystemClock.elapsedRealtime()
                workManager
                    .enqueueUniqueWork(
                        workName,
                        ExistingWorkPolicy.REPLACE,
                        ProtonThumbnailWorker.request(userId),
                    ).result
                    .get()
            }
        }

        override suspend fun cancelAndAwait(userId: UserId) {
            withContext(Dispatchers.IO) {
                workManager.cancelUniqueWork(ProtonWorkNames.legacyThumbnailsWhileCharging(userId)).result.get()
                workManager.cancelUniqueWork(ProtonWorkNames.thumbnails(userId)).result.get()
            }
        }

        override fun clearPaused(userId: UserId) {
            pauseStore.setPaused(userId, paused = false)
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

        /**
         * One subscription per work name for the life of the process. WorkManager pushes state
         * changes from its database, so [Observation.active] is answered without a query, and
         * the run that just ended clears the debounce: the next ask after a run is a new one.
         */
        private fun observe(workName: String): Observation =
            observed.computeIfAbsent(workName) {
                Observation().also { observation ->
                    observationScope.launch {
                        workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                            val requests =
                                infos.map { info ->
                                    ProtonThumbnailQueuedRequest(info.state, info.constraints.requiresCharging())
                                }
                            val active = ProtonThumbnailEnqueuePolicy.isActive(requests.map { it.state })
                            if (observation.active && !active) observation.lastEnqueuedAtMillis = null
                            observation.waitingForCharger = ProtonThumbnailEnqueuePolicy.isWaitingForCharger(requests)
                            observation.active = active
                        }
                    }
                }
            }

        private class Observation {
            @Volatile var active = false

            /** The unfinished requests are all follow-ups queued for the charger; see [ProtonThumbnailEnqueuePolicy]. */
            @Volatile var waitingForCharger = false

            @Volatile var lastEnqueuedAtMillis: Long? = null
        }
    }

/** One request under the unique name as WorkManager reports it. */
internal data class ProtonThumbnailQueuedRequest(
    val state: WorkInfo.State,
    val requiresCharging: Boolean,
)

/** Whether an ask for a run is worth a WorkManager transaction; see [ProtonThumbnailWorkScheduler]. */
internal object ProtonThumbnailEnqueuePolicy {
    /** Asks closer together than this are one ask; a refresh fans out into several within a second. */
    const val DEBOUNCE_MILLIS = 15_000L

    /** A request that is queued, blocked behind a chained run, or running is the run the app wants. */
    fun isActive(states: Collection<WorkInfo.State>): Boolean = states.any { state -> !state.isFinished }

    /**
     * Whether every unfinished request is a charging follow-up still waiting to start. Such a
     * request holds the unique name without doing anything until a charger appears, while an ask
     * from the app is made with the app on screen, where previews are allowed anyway. A running
     * follow-up is a run in progress and never counts as waiting.
     */
    fun isWaitingForCharger(requests: Collection<ProtonThumbnailQueuedRequest>): Boolean {
        val unfinished = requests.filterNot { request -> request.state.isFinished }
        return unfinished.isNotEmpty() &&
            unfinished.all { request -> request.requiresCharging && request.state != WorkInfo.State.RUNNING }
    }

    /** A request waiting for the charger is replaced; anything else queued or going is kept. */
    fun existingWorkPolicy(waitingForCharger: Boolean): ExistingWorkPolicy =
        if (waitingForCharger) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

    fun shouldEnqueue(
        activeRun: Boolean,
        lastEnqueuedAtMillis: Long?,
        nowMillis: Long,
        waitingForCharger: Boolean = false,
    ): Boolean {
        if (activeRun && !waitingForCharger) return false
        if (lastEnqueuedAtMillis == null) return true
        // A clock that went backwards must not silence the ask.
        return nowMillis < lastEnqueuedAtMillis || nowMillis - lastEnqueuedAtMillis >= DEBOUNCE_MILLIS
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonThumbnailSchedulerModule {
    @Binds
    abstract fun bindProtonThumbnailScheduler(implementation: ProtonThumbnailWorkScheduler): ProtonThumbnailScheduler

    @Binds
    abstract fun bindProtonThumbnailFollowUpScheduler(
        implementation: ProtonThumbnailWorkScheduler,
    ): ProtonThumbnailFollowUpScheduler
}
