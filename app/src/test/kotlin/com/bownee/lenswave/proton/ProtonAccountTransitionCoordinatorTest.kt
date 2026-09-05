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

    @Test
    fun `the stored account is preloaded and its first observation only sweeps, records and enqueues`() =
        runBlocking {
            val events = mutableListOf<String>()
            val store = FakeLastAccountStore(stored = UserId("a"))
            val coordinator = coordinator(events, lastAccountStore = store)

            coordinator.preloadLastAccount()
            coordinator.transition(null, UserId("a"), accountAbsent = false)

            assertEquals(listOf("activate:a", "retain:a", "enqueue:a"), events)
            assertEquals(listOf(UserId("a")), store.writes)
        }

    @Test
    fun `a preloaded account Core does not confirm is torn down before the reported one is activated`() =
        runBlocking {
            val events = mutableListOf<String>()
            val store = FakeLastAccountStore(stored = UserId("a"))
            val coordinator = coordinator(events, lastAccountStore = store)

            coordinator.preloadLastAccount()
            coordinator.transition(null, UserId("b"), accountAbsent = false)

            assertEquals(
                listOf("activate:a", "cancel:a", "disconnect:a", "activate:b", "retain:b", "enqueue:b"),
                events,
            )
            assertEquals(listOf(UserId("b")), store.writes)
        }

    @Test
    fun `a preloaded account is torn down when no account is signed in after all`() =
        runBlocking {
            val events = mutableListOf<String>()
            val store = FakeLastAccountStore(stored = UserId("a"))
            val coordinator = coordinator(events, lastAccountStore = store)

            coordinator.preloadLastAccount()
            coordinator.transition(null, null, accountAbsent = true)

            assertEquals(listOf("activate:a", "cancel:a", "disconnect:a", "retain:null"), events)
            assertEquals(listOf(null), store.writes)
        }

    @Test
    fun `a preloaded account survives an observation of an account that is not ready yet`() =
        runBlocking {
            val events = mutableListOf<String>()
            val store = FakeLastAccountStore(stored = UserId("a"))
            val coordinator = coordinator(events, lastAccountStore = store)

            coordinator.preloadLastAccount()
            coordinator.transition(null, null, accountAbsent = false)
            coordinator.transition(null, UserId("a"), accountAbsent = false)

            assertEquals(listOf("activate:a", "retain:a", "enqueue:a"), events)
        }

    @Test
    fun `without a stored account the preload does nothing`() =
        runBlocking {
            val events = mutableListOf<String>()

            coordinator(events).preloadLastAccount()

            assertEquals(emptyList<String>(), events)
        }

    @Test
    fun `every transition records the signed-in account for the next launch`() =
        runBlocking {
            val store = FakeLastAccountStore()
            val coordinator = coordinator(mutableListOf(), lastAccountStore = store)

            coordinator.transition(null, UserId("a"), accountAbsent = false)
            coordinator.transition(UserId("a"), null, accountAbsent = true)
            coordinator.transition(null, null, accountAbsent = true)

            assertEquals(listOf(UserId("a"), null, null), store.writes)
        }

    private fun coordinator(
        events: MutableList<String>,
        sessionLifecycle: ProtonSessionLifecycle = FakeSessionLifecycle(events),
        mutationForgetter: ProtonAccountMutationForgetter = ProtonAccountMutationForgetter {},
        cacheCleaner: ProtonAccountCacheCleaner = ProtonAccountCacheCleaner { userId -> events += "retain:$userId" },
        lastAccountStore: ProtonLastAccountStore = FakeLastAccountStore(),
    ) = ProtonAccountTransitionCoordinator(
        sessionLifecycle = sessionLifecycle,
        cacheCleaner = cacheCleaner,
        thumbnailScheduler = FakeThumbnailScheduler(events),
        mutationForgetter = mutationForgetter,
        lastAccountStore = lastAccountStore,
    )

    private class FakeLastAccountStore(
        private var stored: UserId? = null,
    ) : ProtonLastAccountStore {
        val writes = mutableListOf<UserId?>()

        override fun read(): UserId? = stored

        override fun write(userId: UserId?) {
            stored = userId
            writes += userId
        }
    }

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
