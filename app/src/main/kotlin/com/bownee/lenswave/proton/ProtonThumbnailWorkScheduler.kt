package com.bownee.lenswave.proton

import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
 * The WorkManager calls the scheduler makes, behind an interface so the scheduler's wiring can
 * be exercised on the JVM. A run request is described by a [ProtonThumbnailFollowUp]: the plain
 * run the app asks for is one with no constraint and no delay.
 */
internal interface ProtonThumbnailWorkRequests {
    /** What is under [name] right now; a database query, so callers stay off the main thread. */
    fun uniqueWork(name: String): List<ProtonThumbnailQueuedRequest>

    fun uniqueWorkFlow(name: String): Flow<List<ProtonThumbnailQueuedRequest>>

    fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        userId: UserId,
        request: ProtonThumbnailFollowUp,
    )

    /** Like [enqueueUniqueWork], returning once WorkManager has recorded the request. */
    suspend fun enqueueUniqueWorkAndAwait(
        name: String,
        policy: ExistingWorkPolicy,
        userId: UserId,
        request: ProtonThumbnailFollowUp,
    )

    fun cancelUniqueWork(name: String)

    suspend fun cancelUniqueWorkAndAwait(name: String)
}

internal class WorkManagerProtonThumbnailWorkRequests
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : ProtonThumbnailWorkRequests {
        override fun uniqueWork(name: String): List<ProtonThumbnailQueuedRequest> =
            workManager.getWorkInfosForUniqueWork(name).get().map(::queuedRequest)

        override fun uniqueWorkFlow(name: String): Flow<List<ProtonThumbnailQueuedRequest>> =
            workManager.getWorkInfosForUniqueWorkFlow(name).map { infos -> infos.map(::queuedRequest) }

        override fun enqueueUniqueWork(
            name: String,
            policy: ExistingWorkPolicy,
            userId: UserId,
            request: ProtonThumbnailFollowUp,
        ) {
            workManager.enqueueUniqueWork(name, policy, workRequest(userId, request))
        }

        override suspend fun enqueueUniqueWorkAndAwait(
            name: String,
            policy: ExistingWorkPolicy,
            userId: UserId,
            request: ProtonThumbnailFollowUp,
        ) {
            withContext(Dispatchers.IO) {
                workManager.enqueueUniqueWork(name, policy, workRequest(userId, request)).result.get()
            }
        }

        override fun cancelUniqueWork(name: String) {
            workManager.cancelUniqueWork(name)
        }

        override suspend fun cancelUniqueWorkAndAwait(name: String) {
            withContext(Dispatchers.IO) { workManager.cancelUniqueWork(name).result.get() }
        }

        private fun workRequest(
            userId: UserId,
            request: ProtonThumbnailFollowUp,
        ) = ProtonThumbnailWorker.request(
            userId,
            requiresCharging = request.requiresCharging,
            initialDelayMillis = request.initialDelayMillis,
            networkWaitAttempt = request.networkWaitAttempt,
        )

        private fun queuedRequest(info: WorkInfo) =
            ProtonThumbnailQueuedRequest(info.state, info.constraints.requiresCharging())
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
 *
 * The first ask for a name after the process started does not know yet what WorkManager holds
 * under it, and the request that decides the policy (the charging follow-up) may well be there,
 * persisted by an earlier process. That ask is decided once the state has been read, off the
 * caller's thread; asks that arrive meanwhile wait for the same answer, and the debounce stamp
 * lets exactly one of them reach WorkManager.
 */
@Singleton
internal class ProtonThumbnailWorkScheduler(
    private val workRequests: ProtonThumbnailWorkRequests,
    private val pauseStore: ProtonThumbnailPauseStore,
    private val observationScope: CoroutineScope,
    private val elapsedRealtimeMillis: () -> Long,
    private val reportFailure: (Throwable) -> Unit,
) : ProtonThumbnailScheduler,
    ProtonThumbnailFollowUpScheduler {
    @Inject
    constructor(
        workRequests: ProtonThumbnailWorkRequests,
        pauseStore: ProtonThumbnailPauseStore,
    ) : this(
        workRequests,
        pauseStore,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        SystemClock::elapsedRealtime,
        { error -> LenswaveDiagnostics.reportFailure(LenswaveOperation.THUMBNAIL_WORKER, error) },
    )

    private val legacyWorkCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val observed = ConcurrentHashMap<String, Observation>()

    override fun enqueue(userId: UserId) {
        if (pauseStore.isPaused(userId)) return
        cancelLegacyWork(userId)
        val workName = ProtonWorkNames.thumbnails(userId)
        val observation = observe(workName)
        if (observation.resolved.isCompleted) {
            enqueueResolved(userId, workName, observation)
        } else {
            observationScope.launch {
                observation.resolved.await()
                enqueueResolved(userId, workName, observation)
            }
        }
    }

    /** Decides an ask against a state WorkManager has answered at least once. */
    private fun enqueueResolved(
        userId: UserId,
        workName: String,
        observation: Observation,
    ) {
        val now = elapsedRealtimeMillis()
        val waitingForCharger = observation.waitingForCharger
        val lastEnqueuedAt = observation.lastEnqueuedAtMillis.get()
        if (!ProtonThumbnailEnqueuePolicy.shouldEnqueue(
                observation.active,
                lastEnqueuedAt.takeUnless { it == NEVER_ENQUEUED },
                now,
                waitingForCharger,
            )
        ) {
            return
        }
        // Whoever moves the stamp submits; a concurrent ask in the same window finds it moved.
        if (!observation.lastEnqueuedAtMillis.compareAndSet(lastEnqueuedAt, now)) return
        workRequests.enqueueUniqueWork(
            workName,
            ProtonThumbnailEnqueuePolicy.existingWorkPolicy(waitingForCharger),
            userId,
            ProtonThumbnailFollowUp(requiresCharging = false),
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
        workRequests.enqueueUniqueWork(
            ProtonWorkNames.thumbnails(userId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            userId,
            followUp,
        )
    }

    override suspend fun resume(userId: UserId) {
        enqueue(userId)
    }

    override suspend fun restart(userId: UserId) {
        if (pauseStore.isPaused(userId)) return
        val workName = ProtonWorkNames.thumbnails(userId)
        observe(workName).lastEnqueuedAtMillis.set(elapsedRealtimeMillis())
        workRequests.enqueueUniqueWorkAndAwait(
            workName,
            ExistingWorkPolicy.REPLACE,
            userId,
            ProtonThumbnailFollowUp(requiresCharging = false),
        )
    }

    /**
     * Cancels whatever is queued or going for the account, and stops watching its name: the
     * account is being removed, and an observation kept for it would hold its collector for the
     * rest of the process. An ask for the same account later reads WorkManager afresh.
     */
    override suspend fun cancelAndAwait(userId: UserId) {
        workRequests.cancelUniqueWorkAndAwait(ProtonWorkNames.legacyThumbnailsWhileCharging(userId))
        val workName = ProtonWorkNames.thumbnails(userId)
        workRequests.cancelUniqueWorkAndAwait(workName)
        observed.remove(workName)?.collector?.cancel()
    }

    override fun clearPaused(userId: UserId) {
        pauseStore.setPaused(userId, paused = false)
    }

    /**
     * Earlier versions queued the charging run under its own name. Whatever an upgrade left
     * there would run beside the single name, so each account's legacy name is cancelled once
     * per process (a single flag skipped every account but the first); the cancel is a no-op
     * when nothing is queued.
     */
    private fun cancelLegacyWork(userId: UserId) {
        val legacyName = ProtonWorkNames.legacyThumbnailsWhileCharging(userId)
        if (legacyWorkCancelled.add(legacyName)) workRequests.cancelUniqueWork(legacyName)
    }

    /**
     * One subscription per work name for the life of the process. The state is read once
     * directly, so the first ask is decided on what WorkManager really holds, and then pushed
     * from WorkManager's database as it changes, so [Observation.active] is answered without a
     * query; the run that just ended clears the debounce: the next ask after a run is a new one.
     */
    private fun observe(workName: String): Observation =
        observed.computeIfAbsent(workName) {
            Observation().also { observation ->
                observation.collector =
                    observationScope.launch {
                        // A query that fails still resolves: an ask must not wait forever, and an
                        // ask decided on an empty state is the ask the app made anyway.
                        try {
                            observation.update(workRequests.uniqueWork(workName))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            reportFailure(error)
                        } finally {
                            observation.resolved.complete(Unit)
                        }
                        try {
                            workRequests.uniqueWorkFlow(workName).collect(observation::update)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            reportFailure(error)
                        }
                    }
            }
        }

    private class Observation {
        @Volatile var active = false

        /** The unfinished requests are all follow-ups queued for the charger; see [ProtonThumbnailEnqueuePolicy]. */
        @Volatile var waitingForCharger = false

        /** [NEVER_ENQUEUED] until an ask goes through; cleared again when the run it asked for ends. */
        val lastEnqueuedAtMillis = AtomicLong(NEVER_ENQUEUED)

        /** Completed once WorkManager has answered what it holds under the name. */
        val resolved = CompletableDeferred<Unit>()

        /** The coroutine reading the name; cancelled when the account is removed ([cancelAndAwait]). */
        var collector: Job? = null

        fun update(requests: List<ProtonThumbnailQueuedRequest>) {
            val nowActive = ProtonThumbnailEnqueuePolicy.isActive(requests.map { it.state })
            if (active && !nowActive) lastEnqueuedAtMillis.set(NEVER_ENQUEUED)
            waitingForCharger = ProtonThumbnailEnqueuePolicy.isWaitingForCharger(requests)
            active = nowActive
        }
    }

    private companion object {
        const val NEVER_ENQUEUED = Long.MIN_VALUE
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

    @Binds
    abstract fun bindProtonThumbnailWorkRequests(
        implementation: WorkManagerProtonThumbnailWorkRequests,
    ): ProtonThumbnailWorkRequests
}
