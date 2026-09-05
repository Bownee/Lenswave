package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtonAccountTransitionCoordinatorTest {
    @Test
    fun accountSwitchUsesOneOrderedBarrierAndResumesThumbnailQueue() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events)

            coordinator.transition(UserId("a"), UserId("b"), accountAbsent = false)

            assertEquals(
                listOf("cancel:a", "disconnect:a", "activate:b", "retain:b", "enqueue:b"),
                events,
            )
        }

    @Test
    fun logoutCompletesEveryErasureStepWithoutEnqueueing() =
        runBlocking {
            val events = mutableListOf<String>()

            coordinator(events).transition(UserId("a"), null, accountAbsent = true)

            assertEquals(listOf("cancel:a", "disconnect:a", "retain:null"), events)
        }

    @Test
    fun failedActivationDoesNotEraseCachesOrEnqueueNewWork() {
        val events = mutableListOf<String>()
        val sessionLifecycle = FakeSessionLifecycle(events, failActivation = true)
        val coordinator = coordinator(events, sessionLifecycle)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.transition(UserId("a"), UserId("b"), accountAbsent = false) }
        }
        assertEquals(listOf("cancel:a", "disconnect:a", "activate:b"), events)
    }

    @Test
    fun firstObservationWithoutAnAccountSweepsTheResidueOfPreviousSignOuts() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events)

            coordinator.transition(null, null, accountAbsent = true)
            coordinator.transition(null, null, accountAbsent = true)

            assertEquals(listOf("retain:null"), events)
        }

    @Test
    fun aRealTransitionCountsAsTheSweep() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events)

            coordinator.transition(null, UserId("a"), accountAbsent = false)
            coordinator.transition(UserId("a"), UserId("a"), accountAbsent = false)

            assertEquals(listOf("activate:a", "retain:a", "enqueue:a"), events)
        }

    @Test
    fun aRealTransitionForgetsQueuedMutationOutcomesAfterTheDisconnect() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator =
                coordinator(events, mutationForgetter = ProtonAccountMutationForgetter { events += "forget-mutations" })

            coordinator.transition(UserId("a"), UserId("b"), accountAbsent = false)

            assertEquals(
                listOf("cancel:a", "disconnect:a", "forget-mutations", "activate:b", "retain:b", "enqueue:b"),
                events,
            )
        }

    @Test
    fun anAccountThatIsNotReadyYetIsNotSweptAsIfItWereAbsent() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events)

            // The account exists but has not finished loading: nothing may be deleted.
            coordinator.transition(null, null, accountAbsent = false)
            assertEquals(emptyList<String>(), events)

            // Once it is ready the real transition keeps exactly that account.
            coordinator.transition(null, UserId("a"), accountAbsent = false)
            assertEquals(listOf("activate:a", "retain:a", "enqueue:a"), events)
        }

    @Test
    fun anAccountThatBecomesAbsentAfterLoadingIsSweptOnThatObservation() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events)

            coordinator.transition(null, null, accountAbsent = false)
            coordinator.transition(null, null, accountAbsent = true)

            assertEquals(listOf("retain:null"), events)
        }

    @Test
    fun aSignedInAccountThatStopsBeingReadyIsTornDownWithoutASweep() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events)

            coordinator.transition(UserId("a"), null, accountAbsent = false)

            assertEquals(listOf("cancel:a", "disconnect:a"), events)
        }

    @Test
    fun aSweepThatFailsIsRetriedOnTheNextEqualTransition() =
        runBlocking {
            val events = mutableListOf<String>()
            var failures = 1
            val coordinator =
                coordinator(events, cacheCleaner = { userId ->
                    events += "retain:$userId"
                    if (failures-- > 0) error("cache directory is busy")
                })

            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.transition(null, null, accountAbsent = true) }
            }
            coordinator.transition(null, null, accountAbsent = true)
            coordinator.transition(null, null, accountAbsent = true)

            assertEquals(listOf("retain:null", "retain:null"), events)
        }

    private fun coordinator(
        events: MutableList<String>,
        sessionLifecycle: ProtonSessionLifecycle = FakeSessionLifecycle(events),
        mutationForgetter: ProtonAccountMutationForgetter = ProtonAccountMutationForgetter {},
        cacheCleaner: ProtonAccountCacheCleaner = ProtonAccountCacheCleaner { userId -> events += "retain:$userId" },
    ) = ProtonAccountTransitionCoordinator(
        sessionLifecycle = sessionLifecycle,
        cacheCleaner = cacheCleaner,
        thumbnailScheduler = FakeThumbnailScheduler(events),
        mutationForgetter = mutationForgetter,
    )

    private class FakeThumbnailScheduler(
        private val events: MutableList<String>,
    ) : ProtonThumbnailScheduler {
        override fun enqueue(userId: UserId) {
            events += "enqueue:${userId.id}"
        }

        override fun enqueueWhileCharging(userId: UserId) {
            events += "enqueue-charging:${userId.id}"
        }

        override suspend fun resume(userId: UserId) {
            events += "resume:${userId.id}"
        }

        override suspend fun restart(userId: UserId) {
            events += "restart:${userId.id}"
        }

        override suspend fun cancelAndAwait(userId: UserId) {
            events += "cancel:${userId.id}"
        }
    }

    private class FakeSessionLifecycle(
        private val events: MutableList<String>,
        private val failActivation: Boolean = false,
    ) : ProtonSessionLifecycle {
        override suspend fun activate(userId: UserId) {
            events += "activate:${userId.id}"
            if (failActivation) error("activation failed")
        }

        override suspend fun disconnect(userId: UserId) {
            events += "disconnect:${userId.id}"
        }
    }
}
