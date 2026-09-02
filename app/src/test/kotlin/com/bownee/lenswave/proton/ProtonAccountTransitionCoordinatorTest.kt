package com.bownee.lenswave.proton

import com.bownee.lenswave.gallery.CombinedMatchProgress
import com.bownee.lenswave.gallery.CombinedPhotoMatcher
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtonAccountTransitionCoordinatorTest {
    @Test
    fun accountSwitchUsesOneOrderedBarrierAndEnqueuesOnlyAfterCleanup() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        coordinator.transition(UserId("a"), UserId("b"))

        assertEquals(
            listOf("cancel:a", "disconnect:a", "activate:b", "combined-clear:a", "retain:b", "enqueue:b"),
            events,
        )
    }

    @Test
    fun logoutCompletesEveryErasureStepWithoutEnqueueing() = runBlocking {
        val events = mutableListOf<String>()

        coordinator(events).transition(UserId("a"), null)

        assertEquals(listOf("cancel:a", "disconnect:a", "combined-clear:a", "retain:null"), events)
    }

    @Test
    fun failedActivationDoesNotClearCombinedDataOrEnqueueNewWork() {
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
        combinedPhotoMatcher = FakeCombinedMatcher(events),
        cacheCleaner = ProtonAccountCacheCleaner { userId -> events += "retain:$userId" },
        thumbnailScheduler = FakeThumbnailScheduler(events),
    )

    private class FakeCombinedMatcher(private val events: MutableList<String>) : CombinedPhotoMatcher {
        override suspend fun resolveMatches(
            userId: UserId,
            devicePhotos: List<GalleryAsset>,
            protonPhotos: List<ProtonGalleryPhoto>,
            forceRecheck: Boolean,
            onProgress: suspend (CombinedMatchProgress) -> Unit,
        ) = Unit

        override suspend fun clear(userId: UserId) {
            events += "combined-clear:${userId.id}"
        }
    }

    private class FakeThumbnailScheduler(private val events: MutableList<String>) : ProtonThumbnailScheduler {
        override fun enqueue(userId: UserId) {
            events += "enqueue:${userId.id}"
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
