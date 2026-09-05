package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonThumbnailRunTest {
    private val userId = UserId("user")
    private val gateway = FakeGateway()
    private val followUps = mutableListOf<ProtonThumbnailFollowUp>()
    private val runGuard = ProtonThumbnailRunGuard()
    private val transferCoordinator = ProtonTransferCoordinator()
    private val budget = FakeBudgetStore()
    private val pauseStore = FakePauseStore()
    private val previewAdmission = ProtonPreviewAdmission()
    private val sessionState =
        MutableStateFlow(ProtonAccountSessionState(activeUserId = userId, initialized = true))
    private val network = MutableStateFlow(true)
    private val publications = mutableListOf<ProtonThumbnailNotificationProgress>()
    private val states = mutableListOf<String>()
    private val failures = mutableListOf<Throwable>()
    private var previewsAllowed = false
    private var setForegroundFailure: IllegalStateException? = null
    private var wallClockMillis = 1_700_000_000_000L

    private fun TestScope.newRun(input: ProtonThumbnailRun.Input = ProtonThumbnailRun.Input()) =
        ProtonThumbnailRun(
            userId = userId,
            repository = gateway,
            sessionState = sessionState,
            followUps =
                object : ProtonThumbnailFollowUpScheduler {
                    override fun enqueueFollowUp(
                        userId: UserId,
                        followUp: ProtonThumbnailFollowUp,
                    ) {
                        followUps += followUp
                    }
                },
            runGuard = runGuard,
            transferCoordinator = transferCoordinator,
            foregroundBudget = budget,
            clock =
                object : LenswaveClock {
                    override fun nowMillis(): Long = wallClockMillis
                },
            pauseStore = pauseStore,
            previewAdmission = previewAdmission,
            input = input,
            elapsedRealtimeMillis = { testScheduler.currentTime },
            previewsAllowed = { previewsAllowed },
            awaitValidatedUnmeteredNetwork = { timeoutMillis ->
                network.value || withTimeoutOrNull(timeoutMillis) { network.first { it } } != null
            },
            setForeground = { progress ->
                setForegroundFailure?.let { throw it }
                publications += progress
            },
            reportFailure = { error -> failures += error },
            reportState = { state -> states += state },
        )

    private fun assertEnded(
        expected: ProtonThumbnailWorkOutcome,
        result: ProtonThumbnailRunResult,
    ) {
        assertEquals(ProtonThumbnailRunResult.Ended(expected), result)
        assertEquals(expected.diagnosticState, states.last())
    }

    @Test
    fun `a paused run ends before the queues and owes nothing`() =
        runTest {
            pauseStore.paused = true
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)

            val result = newRun(ProtonThumbnailRun.Input(replacedChargingRun = true)).execute()

            assertEnded(ProtonThumbnailWorkOutcome.PAUSED, result)
            assertEquals(0, gateway.batchCalls)
            assertTrue(followUps.isEmpty())
            assertFalse(runGuard.isActive)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `a run that finds another one active ends at once and leaves the guard to it`() =
        runTest {
            assertTrue(runGuard.tryBegin())

            val result = newRun(ProtonThumbnailRun.Input(replacedChargingRun = true)).execute()

            assertEnded(ProtonThumbnailWorkOutcome.ALREADY_RUNNING, result)
            assertEquals(0, gateway.batchCalls)
            assertTrue("the other run's guard is not released", runGuard.isActive)
            // The charging follow-up this run displaced is given back.
            assertEquals(
                listOf(
                    ProtonThumbnailFollowUp(
                        requiresCharging = true,
                        initialDelayMillis = ProtonThumbnailFollowUpPolicy.RESTORED_CHARGING_RUN_DELAY_MILLIS,
                    ),
                ),
                followUps,
            )
        }

    @Test
    fun `a session that never settles ends the run after the session wait`() =
        runTest {
            sessionState.value = ProtonAccountSessionState(initialized = true, transitioning = true)
            previewsAllowed = false

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.SESSION_UNAVAILABLE, result)
            assertEquals(ProtonThumbnailRun.SESSION_READY_TIMEOUT_MILLIS, testScheduler.currentTime)
            assertEquals(0, gateway.batchCalls)
            assertFalse(runGuard.isActive)
            assertTrue("the admission gate is unbound again", previewAdmission.previewsAllowed())
            assertTrue(followUps.isEmpty())
        }

    @Test
    fun `a session settling late is waited for`() =
        runTest {
            sessionState.value = ProtonAccountSessionState(transitioning = true)
            gateway.progress = ProtonThumbnailWorkProgress(stored = 3, pending = 0)
            launch {
                delay(2_000L)
                sessionState.value = ProtonAccountSessionState(activeUserId = userId, initialized = true)
            }

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.COMPLETE, result)
            assertEquals(2_000L, testScheduler.currentTime)
        }

    @Test
    fun `a session for another user fails the run without a follow-up`() =
        runTest {
            sessionState.value = ProtonAccountSessionState(activeUserId = UserId("other"), initialized = true)

            val result = newRun().execute()

            assertEquals(ProtonThumbnailRunResult.Failed, result)
            assertTrue(states.isEmpty())
            assertTrue(followUps.isEmpty())
            assertFalse(runGuard.isActive)
        }

    @Test
    fun `nothing pending ends complete without touching the network or the notification`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 3, pending = 0)
            network.value = false

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.COMPLETE, result)
            assertEquals(0, gateway.batchCalls)
            assertTrue(publications.isEmpty())
            assertTrue(followUps.isEmpty())
            assertTrue(budget.recorded.isEmpty())
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `previews waiting for the charger end the run deferred with a charging follow-up`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 3, pending = 0, previewsPending = 5)
            previewsAllowed = false

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.PREVIEWS_DEFERRED, result)
            assertEquals(listOf(ProtonThumbnailFollowUp(requiresCharging = true)), followUps)
            assertEquals(0, gateway.batchCalls)
        }

    @Test
    fun `no validated network ends the run waiting, one rung further up the backoff`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            network.value = false

            val result = newRun(ProtonThumbnailRun.Input(networkWaitAttempt = 2)).execute()

            assertEnded(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, result)
            assertEquals(ProtonThumbnailRun.NETWORK_READY_TIMEOUT_MILLIS, testScheduler.currentTime)
            assertTrue("never promoted", publications.isEmpty())
            assertTrue(budget.recorded.isEmpty())
            assertEquals(
                listOf(
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(2),
                        networkWaitAttempt = 3,
                    ),
                ),
                followUps,
            )
        }

    @Test
    fun `batches are processed under the notification until the queues are idle`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 2)
            gateway.steps += ProtonThumbnailQueueStep.Processed
            gateway.steps += ProtonThumbnailQueueStep.Processed
            gateway.steps += ProtonThumbnailQueueStep.Idle(hasPending = false)
            previewsAllowed = true
            advanceTimeBy(10_000L)

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.COMPLETE, result)
            assertEquals(3, gateway.batchCalls)
            assertEquals(listOf(true, true, true), gateway.allowPreviewsAsked)
            assertTrue(followUps.isEmpty())
            // The first publication is forced and shows the starting counts; the last shows the end.
            assertEquals(ProtonThumbnailNotificationProgress(downloaded = 0, total = 2), publications.first())
            assertEquals(ProtonThumbnailNotificationProgress(downloaded = 2, total = 2), publications.last())
            assertFalse(runGuard.isActive)
            assertTrue(previewAdmission.previewsAllowed())
        }

    @Test
    fun `the downloader's admission gate is the run's previews answer while the run is on`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 1)
            previewsAllowed = false
            var gateDuringBatch: Boolean? = null
            gateway.onExhausted = {
                gateDuringBatch = previewAdmission.previewsAllowed()
                ProtonThumbnailQueueStep.Idle(hasPending = false)
            }

            newRun().execute()

            assertEquals(false, gateDuringBatch)
            assertTrue("unbound: the default answer applies again", previewAdmission.previewsAllowed())
        }

    @Test
    fun `progress publications are rate limited and the run's foreground time is recorded`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 100)
            var reportedBatches = 0
            gateway.onExhausted = {
                if (reportedBatches++ < 10) {
                    // A batch of a few files a hundred milliseconds apart.
                    gateway.report(gateway.progress.copy(stored = reportedBatches, pending = 100 - reportedBatches))
                    delay(100L)
                    ProtonThumbnailQueueStep.Processed
                } else {
                    ProtonThumbnailQueueStep.Idle(hasPending = false)
                }
            }

            newRun().execute()

            // The forced first and last publications, plus one per publication interval.
            assertTrue(publications.size.toString(), publications.size in 2..4)
            assertEquals(
                listOf(ProtonForegroundRun(endedAtMillis = wallClockMillis, durationMillis = 1_000L)),
                budget.recorded,
            )
        }

    @Test
    fun `a batch that got through resets the network backoff ladder`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            gateway.steps += ProtonThumbnailQueueStep.Processed
            gateway.onExhausted = {
                network.value = false
                ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = 2_000L)
            }

            val result = newRun(ProtonThumbnailRun.Input(networkWaitAttempt = 4)).execute()

            assertEnded(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, result)
            assertEquals(
                listOf(
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = ProtonThumbnailFollowUpPolicy.networkWaitDelayMillis(0),
                        networkWaitAttempt = 1,
                    ),
                ),
                followUps,
            )
        }

    @Test
    fun `a short retry is slept through and a long one ends the run with its delay`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            gateway.steps += ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = 2_000L)
            gateway.steps += ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = 15L * 60L * 1_000L)

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, result)
            assertEquals(2, gateway.batchCalls)
            assertEquals(2_000L + ProtonThumbnailRun.IDLE_WAIT_SLACK_MILLIS, testScheduler.currentTime)
            assertEquals(
                listOf(ProtonThumbnailFollowUp(requiresCharging = false, initialDelayMillis = 15L * 60L * 1_000L)),
                followUps,
            )
        }

    @Test
    fun `an idle step that saw previews deferred is remembered when the run ends on a lost network`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 0, previewsPending = 4)
            previewsAllowed = true
            gateway.onExhausted = {
                previewsAllowed = false
                network.value = false
                ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = 1_000L, previewsDeferred = true)
            }

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.WAITING_FOR_NETWORK, result)
            assertEquals(listOf(ProtonThumbnailFollowUp(requiresCharging = true)), followUps)
        }

    @Test
    fun `the run ends at its limit and the follow-up carries the rest of the work`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10_000)
            gateway.onExhausted = {
                delay(60_000L)
                ProtonThumbnailQueueStep.Processed
            }

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.TIMED_OUT, result)
            assertEquals(ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS, testScheduler.currentTime)
            assertEquals(
                listOf(
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = ProtonThumbnailFollowUpPolicy.RUN_LIMIT_DELAY_MILLIS,
                    ),
                ),
                followUps,
            )
            assertEquals(
                listOf(
                    ProtonForegroundRun(
                        endedAtMillis = wallClockMillis,
                        durationMillis = ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS,
                    ),
                ),
                budget.recorded,
            )
        }

    @Test
    fun `a follow-up waits for the day's foreground allowance`() =
        runTest {
            val hour = 60L * 60L * 1_000L
            budget.runs +=
                ProtonForegroundRun(
                    endedAtMillis = wallClockMillis - hour,
                    durationMillis = ProtonThumbnailForegroundBudgetPolicy.BUDGET_MILLIS - hour,
                )
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            gateway.steps += ProtonThumbnailQueueStep.Idle(hasPending = true, retryAfterMillis = 15L * 60L * 1_000L)

            newRun().execute()

            assertEquals(
                ProtonThumbnailForegroundBudgetPolicy.WINDOW_MILLIS - hour,
                followUps.single().initialDelayMillis,
            )
        }

    @Test
    fun `a cancelled run records its foreground time and lets go of the guard`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            gateway.onExhausted = { awaitCancellation() }
            val job = launch { newRun().execute() }
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(runGuard.isActive)

            job.cancelAndJoin()

            assertEquals(listOf("interrupted"), states)
            assertEquals(
                listOf(ProtonForegroundRun(endedAtMillis = wallClockMillis, durationMillis = 10_000L)),
                budget.recorded,
            )
            assertFalse(runGuard.isActive)
            assertTrue(previewAdmission.previewsAllowed())
            assertTrue(followUps.isEmpty())
        }

    @Test
    fun `a refused promotion keeps the run going without a notification until the allowance is up`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10_000)
            setForegroundFailure = ForegroundServiceStartNotAllowedException()
            gateway.onExhausted = {
                delay(60_000L)
                ProtonThumbnailQueueStep.Processed
            }

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.FOREGROUND_UNAVAILABLE, result)
            assertEquals("background-only", states.first())
            val allowance = ProtonThumbnailForegroundBudgetPolicy.BACKGROUND_ONLY_RUN_MILLIS
            assertTrue(testScheduler.currentTime >= allowance)
            assertTrue(testScheduler.currentTime < allowance + 60_000L)
            assertTrue(gateway.batchCalls > 0)
            assertTrue("never promoted, so nothing to record", budget.recorded.isEmpty())
            assertTrue(failures.isEmpty())
            assertEquals(
                listOf(
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = ProtonThumbnailForegroundBudgetPolicy.FOREGROUND_REFUSED_DELAY_MILLIS,
                    ),
                ),
                followUps,
            )
        }

    @Test
    fun `any other illegal state from the promotion is a crash that is retried`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            val error = IllegalStateException("not a refusal")
            setForegroundFailure = error

            val result = newRun().execute()

            assertEquals(ProtonThumbnailRunResult.Retry, result)
            assertEquals(listOf<Throwable>(error), failures)
            assertEquals(listOf("retry-error"), states)
            assertFalse(runGuard.isActive)
        }

    @Test
    fun `a crash on the last attempt fails the run`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            val error = IOException("disk")
            gateway.onExhausted = { throw error }

            val result =
                newRun(
                    ProtonThumbnailRun.Input(runAttemptCount = ProtonThumbnailWorkPolicy.MAX_ERROR_ATTEMPTS - 1),
                ).execute()

            assertEquals(ProtonThumbnailRunResult.Failed, result)
            // Coroutines' stack-trace recovery re-throws a copy; the class and message survive.
            assertEquals("disk", (failures.single() as IOException).message)
            assertEquals(listOf("stopped-error"), states)
            assertTrue(followUps.isEmpty())
            // Promoted before the crash, so the time still counts.
            assertEquals(1, budget.recorded.size)
        }

    @Test
    fun `a busy viewer is yielded to and a viewer that stays busy ends the run`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 10)
            backgroundScope.launch { transferCoordinator.withForegroundTransfer { awaitCancellation() } }
            runCurrent()

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.WAITING_FOR_RETRY, result)
            assertEquals(0, gateway.batchCalls)
            assertEquals(listOf(false, true, true, true, false), publications.map { it.yielding })
            assertEquals(
                listOf(
                    ProtonThumbnailFollowUp(
                        requiresCharging = false,
                        initialDelayMillis = ProtonThumbnailWorkPolicy.FOREGROUND_BUSY_END_DELAY_MILLIS,
                    ),
                ),
                followUps,
            )
        }

    @Test
    fun `a viewer that finishes lets the run claim again and drops the yielding notice`() =
        runTest {
            gateway.progress = ProtonThumbnailWorkProgress(stored = 0, pending = 1)
            gateway.steps += ProtonThumbnailQueueStep.Idle(hasPending = false)
            val viewer = backgroundScope.launch { transferCoordinator.withForegroundTransfer { awaitCancellation() } }
            runCurrent()
            launch {
                delay(ProtonThumbnailRun.FOREGROUND_YIELD_TIMEOUT_MILLIS + 1_000L)
                viewer.cancelAndJoin()
            }

            val result = newRun().execute()

            assertEnded(ProtonThumbnailWorkOutcome.COMPLETE, result)
            assertEquals(1, gateway.batchCalls)
            assertEquals(listOf(false, true, false, false), publications.map { it.yielding })
        }

    private class FakeGateway : ProtonThumbnailWorkGateway {
        var progress = ProtonThumbnailWorkProgress(stored = 0, pending = 0)
        val steps = ArrayDeque<ProtonThumbnailQueueStep>()
        var onExhausted: suspend () -> ProtonThumbnailQueueStep = { ProtonThumbnailQueueStep.Idle(hasPending = false) }
        var batchCalls = 0
        val allowPreviewsAsked = mutableListOf<Boolean>()
        private var onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit = {}

        /** Reports [next] the way a batch reports the files it stored. */
        suspend fun report(next: ProtonThumbnailWorkProgress) {
            progress = next
            onProgress(next)
        }

        override suspend fun downloadNextQueuedThumbnailBatch(
            userId: UserId,
            allowPreviews: Boolean,
            onProgress: suspend (ProtonThumbnailWorkProgress) -> Unit,
        ): ProtonThumbnailQueueStep {
            batchCalls++
            allowPreviewsAsked += allowPreviews
            this.onProgress = onProgress
            val step = steps.removeFirstOrNull() ?: onExhausted()
            if (step == ProtonThumbnailQueueStep.Processed && progress.pending > 0) {
                report(progress.copy(stored = progress.stored + 1, pending = progress.pending - 1))
            }
            return step
        }

        override suspend fun thumbnailWorkProgress(userId: UserId): ProtonThumbnailWorkProgress = progress
    }

    private class FakeBudgetStore : ProtonThumbnailForegroundBudgetStore {
        val runs = mutableListOf<ProtonForegroundRun>()
        val recorded = mutableListOf<ProtonForegroundRun>()

        override fun runs(userId: UserId): List<ProtonForegroundRun> = runs

        override fun record(
            userId: UserId,
            run: ProtonForegroundRun,
            nowMillis: Long,
        ) {
            recorded += run
            runs += run
        }
    }

    private class FakePauseStore : ProtonThumbnailPauseStore {
        var paused = false

        override fun isPaused(userId: UserId): Boolean = paused

        override fun setPaused(
            userId: UserId,
            paused: Boolean,
        ) {
            this.paused = paused
        }
    }
}
