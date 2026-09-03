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

            coordinator.transition(UserId("a"), UserId("b"))

            assertEquals(
                listOf("cancel:a", "disconnect:a", "activate:b", "retain:b", "enqueue:b"),
                events,
            )
        }

    @Test
    fun logoutCompletesEveryErasureStepWithoutEnqueueing() =
        runBlocking {
            val events = mutableListOf<String>()

            coordinator(events).transition(UserId("a"), null)

            assertEquals(listOf("cancel:a", "disconnect:a", "retain:null"), events)
        }

    @Test
    fun failedActivationDoesNotEraseCachesOrEnqueueNewWork() {
        val events = mutableListOf<String>()
        val sessionLifecycle = FakeSessionLifecycle(events, failActivation = true)
        val coordinator = coordinator(events, sessionLifecycle)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.transition(UserId("a"), UserId("b")) }
        }
        assertEquals(listOf("cancel:a", "disconnect:a", "activate:b"), events)
    }

    private fun coordinator(
        events: MutableList<String>,
        sessionLifecycle: ProtonSessionLifecycle = FakeSessionLifecycle(events),
    ) = ProtonAccountTransitionCoordinator(
        sessionLifecycle = sessionLifecycle,
        cacheCleaner = ProtonAccountCacheCleaner { userId -> events += "retain:$userId" },
        thumbnailScheduler = FakeThumbnailScheduler(events),
    )

    private class FakeThumbnailScheduler(
        private val events: MutableList<String>,
    ) : ProtonThumbnailScheduler {
        override fun enqueue(userId: UserId) {
            events += "enqueue:${userId.id}"
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
