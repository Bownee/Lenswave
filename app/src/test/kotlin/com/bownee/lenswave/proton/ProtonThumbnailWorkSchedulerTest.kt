package com.bownee.lenswave.proton

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonThumbnailWorkSchedulerTest {
    private val workRequests = FakeWorkRequests()
    private val pauseStore = FakePauseStore()
    private val failures = mutableListOf<Throwable>()
    private var now = 1_000L

    private fun test(block: suspend TestScope.(ProtonThumbnailWorkScheduler) -> Unit) =
        runTest {
            block(ProtonThumbnailWorkScheduler(workRequests, pauseStore, backgroundScope, { now }, failures::add))
        }

    @Test
    fun `the first ask replaces a charging follow-up an earlier process left behind`() =
        test { scheduler ->
            workRequests.states[NAME] =
                listOf(ProtonThumbnailQueuedRequest(WorkInfo.State.ENQUEUED, requiresCharging = true))

            scheduler.enqueue(USER)
            assertTrue("decided only once WorkManager has answered", workRequests.enqueued.isEmpty())
            testScheduler.runCurrent()

            val enqueued = workRequests.enqueued.single()
            assertEquals(NAME, enqueued.name)
            assertEquals(ExistingWorkPolicy.REPLACE, enqueued.policy)
            assertEquals(ProtonThumbnailFollowUp(requiresCharging = false), enqueued.request)
        }

    @Test
    fun `an ask while a plain run is queued or going is nothing`() =
        test { scheduler ->
            workRequests.states[NAME] =
                listOf(ProtonThumbnailQueuedRequest(WorkInfo.State.RUNNING, requiresCharging = false))

            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            scheduler.enqueue(USER)
            testScheduler.runCurrent()

            assertTrue(workRequests.enqueued.isEmpty())
        }

    @Test
    fun `asks within the debounce are one ask and a finished run clears it`() =
        test { scheduler ->
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals(1, workRequests.enqueued.size)
            assertEquals(ExistingWorkPolicy.KEEP, workRequests.enqueued.single().policy)

            // The fake reports the queued request; further asks are answered from memory.
            now += ProtonThumbnailEnqueuePolicy.DEBOUNCE_MILLIS - 1
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals(1, workRequests.enqueued.size)

            // Past the debounce, but the run is still queued: still the run the app wants.
            now += 1
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals(1, workRequests.enqueued.size)

            // The run ended: the next ask is a new one, however soon it comes.
            workRequests.finish(NAME)
            testScheduler.runCurrent()
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals(2, workRequests.enqueued.size)
        }

    @Test
    fun `asks that arrive before the first answer are decided once and only one is submitted`() =
        test { scheduler ->
            scheduler.enqueue(USER)
            scheduler.enqueue(USER)
            scheduler.enqueue(USER)
            testScheduler.runCurrent()

            assertEquals(1, workRequests.enqueued.size)
        }

    @Test
    fun `a restart replaces whatever is there and stamps the debounce`() =
        test { scheduler ->
            workRequests.states[NAME] =
                listOf(ProtonThumbnailQueuedRequest(WorkInfo.State.RUNNING, requiresCharging = false))

            // WorkManager reports the replacement a moment after recording it; an ask in that
            // moment must not find the name empty and enqueue a second run.
            workRequests.lagging = true
            scheduler.restart(USER)
            testScheduler.runCurrent()

            val restarted = workRequests.enqueued.single()
            assertEquals(ExistingWorkPolicy.REPLACE, restarted.policy)
            assertTrue(restarted.awaited)
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals("the restart's stamp silences an ask right after it", 1, workRequests.enqueued.size)

            workRequests.publishLagging()
            testScheduler.runCurrent()
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals("and the replacement is the run the app wants", 1, workRequests.enqueued.size)
        }

    @Test
    fun `a pause blocks every request until a refresh lifts it`() =
        test { scheduler ->
            pauseStore.setPaused(USER, paused = true)

            scheduler.enqueue(USER)
            scheduler.enqueueFollowUp(USER, ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = 5L))
            scheduler.enqueueWhileCharging(USER)
            scheduler.restart(USER)
            testScheduler.runCurrent()
            assertTrue(workRequests.enqueued.isEmpty())

            scheduler.clearPaused(USER)
            assertFalse(pauseStore.isPaused(USER))
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals(1, workRequests.enqueued.size)
        }

    @Test
    fun `a follow-up is chained after the current run with its constraints and delay`() =
        test { scheduler ->
            val followUp =
                ProtonThumbnailFollowUp(requiresCharging = true, initialDelayMillis = 60_000L, networkWaitAttempt = 2)

            scheduler.enqueueFollowUp(USER, followUp)

            val enqueued = workRequests.enqueued.single()
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, enqueued.policy)
            assertEquals(followUp, enqueued.request)
            assertEquals(USER, enqueued.userId)
        }

    @Test
    fun `each account's legacy charging name is cancelled once per process`() =
        test { scheduler ->
            scheduler.enqueue(USER)
            scheduler.enqueueWhileCharging(USER)
            scheduler.enqueue(OTHER_USER)
            scheduler.enqueueWhileCharging(OTHER_USER)
            testScheduler.runCurrent()

            assertEquals(
                listOf(
                    ProtonWorkNames.legacyThumbnailsWhileCharging(USER),
                    ProtonWorkNames.legacyThumbnailsWhileCharging(OTHER_USER),
                ),
                workRequests.cancelled,
            )
        }

    @Test
    fun `cancelling an account drops its observation so a later ask reads WorkManager afresh`() =
        test { scheduler ->
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals("one subscription answers every ask", 1, workRequests.queries)
            val collectorsBefore = backgroundScope.coroutineContext[Job]!!.children.count { it.isActive }

            scheduler.cancelAndAwait(USER)
            testScheduler.runCurrent()

            assertEquals(collectorsBefore - 1, backgroundScope.coroutineContext[Job]!!.children.count { it.isActive })
            workRequests.finish(NAME)
            scheduler.enqueue(USER)
            testScheduler.runCurrent()
            assertEquals(2, workRequests.queries)
            assertEquals(2, workRequests.enqueued.size)
        }

    @Test
    fun `cancelling awaits both names`() =
        test { scheduler ->
            scheduler.cancelAndAwait(USER)

            assertEquals(listOf(ProtonWorkNames.legacyThumbnailsWhileCharging(USER), NAME), workRequests.cancelled)
        }

    @Test
    fun `a query that fails still lets the ask through`() =
        test { scheduler ->
            workRequests.failQueries = true

            scheduler.enqueue(USER)
            testScheduler.runCurrent()

            assertEquals(1, workRequests.enqueued.size)
            assertEquals(1, failures.size)
        }

    private class Enqueued(
        val name: String,
        val policy: ExistingWorkPolicy,
        val userId: UserId,
        val request: ProtonThumbnailFollowUp,
        val awaited: Boolean,
    )

    /** Remembers requests and mirrors what WorkManager would report under each name. */
    private class FakeWorkRequests : ProtonThumbnailWorkRequests {
        val states = mutableMapOf<String, List<ProtonThumbnailQueuedRequest>>()
        val enqueued = mutableListOf<Enqueued>()
        val cancelled = mutableListOf<String>()
        var failQueries = false
        var queries = 0

        /** While set, recorded requests are not reported until [publishLagging]. */
        var lagging = false
        private val pendingReports = mutableMapOf<String, List<ProtonThumbnailQueuedRequest>>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<ProtonThumbnailQueuedRequest>>>()

        fun finish(name: String) {
            publish(name, states[name].orEmpty().map { it.copy(state = WorkInfo.State.SUCCEEDED) })
        }

        fun publishLagging() {
            lagging = false
            pendingReports.forEach { (name, requests) -> publish(name, requests) }
            pendingReports.clear()
        }

        override fun uniqueWork(name: String): List<ProtonThumbnailQueuedRequest> {
            queries++
            if (failQueries) throw IllegalStateException("WorkManager is not there")
            return states[name].orEmpty()
        }

        override fun uniqueWorkFlow(name: String): Flow<List<ProtonThumbnailQueuedRequest>> = flow(name)

        override fun enqueueUniqueWork(
            name: String,
            policy: ExistingWorkPolicy,
            userId: UserId,
            request: ProtonThumbnailFollowUp,
        ) {
            record(name, policy, userId, request, awaited = false)
        }

        override suspend fun enqueueUniqueWorkAndAwait(
            name: String,
            policy: ExistingWorkPolicy,
            userId: UserId,
            request: ProtonThumbnailFollowUp,
        ) {
            record(name, policy, userId, request, awaited = true)
        }

        override fun cancelUniqueWork(name: String) {
            cancelled += name
        }

        override suspend fun cancelUniqueWorkAndAwait(name: String) {
            cancelled += name
        }

        private fun record(
            name: String,
            policy: ExistingWorkPolicy,
            userId: UserId,
            request: ProtonThumbnailFollowUp,
            awaited: Boolean,
        ) {
            enqueued += Enqueued(name, policy, userId, request, awaited)
            val queued = ProtonThumbnailQueuedRequest(WorkInfo.State.ENQUEUED, request.requiresCharging)
            val unfinished = states[name].orEmpty().filterNot { it.state.isFinished }
            val next =
                when (policy) {
                    ExistingWorkPolicy.KEEP -> if (unfinished.isEmpty()) listOf(queued) else unfinished
                    ExistingWorkPolicy.REPLACE -> listOf(queued)
                    ExistingWorkPolicy.APPEND, ExistingWorkPolicy.APPEND_OR_REPLACE -> unfinished + queued
                }
            if (lagging) pendingReports[name] = next else publish(name, next)
        }

        private fun publish(
            name: String,
            requests: List<ProtonThumbnailQueuedRequest>,
        ) {
            states[name] = requests
            flow(name).value = requests
        }

        private fun flow(name: String) = flows.getOrPut(name) { MutableStateFlow(states[name].orEmpty()) }
    }

    private class FakePauseStore : ProtonThumbnailPauseStore {
        private val paused = mutableSetOf<String>()

        override fun isPaused(userId: UserId): Boolean = userId.id in paused

        override fun setPaused(
            userId: UserId,
            paused: Boolean,
        ) {
            if (paused) this.paused += userId.id else this.paused -= userId.id
        }
    }

    private companion object {
        val USER = UserId("user")
        val OTHER_USER = UserId("other")
        val NAME = ProtonWorkNames.thumbnails(USER)
    }
}
