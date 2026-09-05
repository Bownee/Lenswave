package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.PhotoMutationResult
import com.bownee.lenswave.gallery.ProtonPhotoMutations
import com.bownee.lenswave.proton.ProtonFavoriteResult
import com.bownee.lenswave.proton.ProtonSessionChangedException
import com.bownee.lenswave.proton.ProtonTrashResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerMutationCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val mutations = FakeMutations()
    private val request = PhotoRequest("s1", "n1", "u", 0L, "one.jpg")

    @Test
    fun `a favourite call stays in flight until it answers and its outcome waits to be consumed`() =
        runTest(dispatcher) {
            val coordinator = coordinator()

            assertTrue(coordinator.setFavorite(UserId("u"), request, favorite = true))
            assertFalse("one mutation per photo at a time", coordinator.setFavorite(UserId("u"), request, true))
            runCurrent()
            assertTrue(coordinator.isInFlight("s1"))
            assertTrue(coordinator.outcomes.value.isEmpty())

            mutations.favoriteAnswers.single().complete(ProtonFavoriteResult(updatedCount = 1))
            runCurrent()

            assertFalse(coordinator.isInFlight("s1"))
            val outcome = ViewerMutationCoordinator.Outcome.FavoriteSet("s1", favorite = true, succeeded = true)
            assertEquals(listOf(outcome), coordinator.outcomes.value)

            coordinator.consume(outcome)
            assertTrue(coordinator.outcomes.value.isEmpty())
        }

    @Test
    fun `a failed or throwing call is reported as an unsuccessful outcome`() =
        runTest(dispatcher) {
            val coordinator = coordinator()

            coordinator.trash(UserId("u"), request)
            runCurrent()
            mutations.trashAnswers.single().complete(ProtonTrashResult(trashedCount = 0, failedCount = 1))
            runCurrent()
            coordinator.setFavorite(UserId("u"), request, favorite = false)
            runCurrent()
            mutations.favoriteAnswers.single().completeExceptionally(IllegalStateException("offline"))
            runCurrent()

            assertEquals(
                listOf(
                    ViewerMutationCoordinator.Outcome.Trashed("s1", succeeded = false),
                    ViewerMutationCoordinator.Outcome.FavoriteSet("s1", favorite = false, succeeded = false),
                ),
                coordinator.outcomes.value,
            )
            assertFalse(coordinator.isInFlight("s1"))
        }

    @Test
    fun `a session change during the call is a failed outcome, not a lost one`() =
        runTest(dispatcher) {
            val coordinator = coordinator()

            assertTrue(coordinator.trash(UserId("u"), request))
            runCurrent()
            mutations.trashAnswers.single().completeExceptionally(ProtonSessionChangedException())
            runCurrent()

            assertEquals(
                listOf(ViewerMutationCoordinator.Outcome.Trashed("s1", succeeded = false)),
                coordinator.outcomes.value,
            )
            assertFalse("the photo is free for the next call", coordinator.isInFlight("s1"))
        }

    @Test
    fun `forgetting everything clears queued outcomes and in-flight marks`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            coordinator.setFavorite(UserId("u"), request, favorite = true)
            runCurrent()
            mutations.favoriteAnswers.single().complete(ProtonFavoriteResult(updatedCount = 1))
            runCurrent()
            coordinator.trash(UserId("u"), request)
            runCurrent()
            assertTrue(coordinator.outcomes.value.isNotEmpty())
            assertTrue(coordinator.isInFlight("s1"))

            coordinator.forgetAll()

            assertTrue(coordinator.outcomes.value.isEmpty())
            assertFalse(coordinator.isInFlight("s1"))
            // The forgotten call still answers, into an epoch that is closed: the outcome belongs
            // to the account that went and never reaches the next account's queue.
            mutations.trashAnswers.single().complete(ProtonTrashResult(trashedCount = 1, failedCount = 0))
            runCurrent()
            assertTrue(coordinator.outcomes.value.isEmpty())
            assertFalse(coordinator.isInFlight("s1"))
        }

    @Test
    fun `a disowned call does not release a photo the next account has claimed`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            coordinator.trash(UserId("u"), request)
            runCurrent()

            coordinator.forgetAll()
            assertTrue("the photo is free at once", coordinator.setFavorite(UserId("v"), request, favorite = true))
            runCurrent()
            mutations.trashAnswers.single().complete(ProtonTrashResult(trashedCount = 1, failedCount = 0))
            runCurrent()

            assertTrue("the new claim survives the old call ending", coordinator.isInFlight("s1"))
            assertTrue(coordinator.outcomes.value.isEmpty())
            mutations.favoriteAnswers.single().complete(ProtonFavoriteResult(updatedCount = 1))
            runCurrent()
            assertEquals(
                listOf(ViewerMutationCoordinator.Outcome.FavoriteSet("s1", favorite = true, succeeded = true)),
                coordinator.outcomes.value,
            )
            assertFalse(coordinator.isInFlight("s1"))
        }

    @Test
    fun `consuming several outcomes at once emits a single queue`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val other = PhotoRequest("s2", "n2", "u", 0L, "two.jpg")
            coordinator.setFavorite(UserId("u"), request, favorite = true)
            coordinator.setFavorite(UserId("u"), other, favorite = true)
            coordinator.trash(UserId("u"), PhotoRequest("s3", "n3", "u", 0L, "three.jpg"))
            runCurrent()
            mutations.favoriteAnswers.forEach { answer -> answer.complete(ProtonFavoriteResult(updatedCount = 1)) }
            mutations.trashAnswers.single().complete(ProtonTrashResult(trashedCount = 1, failedCount = 0))
            runCurrent()
            val queued = coordinator.outcomes.value
            assertEquals(3, queued.size)
            val seen = mutableListOf<List<ViewerMutationCoordinator.Outcome>>()
            val collector = backgroundScope.launch { coordinator.outcomes.collect(seen::add) }
            runCurrent()

            coordinator.consumeAll(queued.take(2))
            runCurrent()

            assertEquals(listOf(queued, queued.drop(2)), seen)
            collector.cancel()
        }

    private fun TestScope.coordinator() =
        ViewerMutationCoordinator(
            photoMutations = mutations,
            deletionExecutor = mutations,
            scope = backgroundScope,
        )

    private class FakeMutations :
        ProtonPhotoMutations,
        PhotoDeletionExecutor {
        val favoriteAnswers = mutableListOf<CompletableDeferred<ProtonFavoriteResult>>()
        val trashAnswers = mutableListOf<CompletableDeferred<ProtonTrashResult>>()

        override suspend fun setFavorite(
            userId: UserId,
            nodeUids: Collection<String>,
            favorite: Boolean,
        ): ProtonFavoriteResult = CompletableDeferred<ProtonFavoriteResult>().also(favoriteAnswers::add).await()

        override suspend fun trashPhotos(
            userId: UserId,
            nodeUids: Collection<String>,
        ): ProtonTrashResult = CompletableDeferred<ProtonTrashResult>().also(trashAnswers::add).await()

        override suspend fun trashProton(
            userId: UserId,
            nodeUids: Collection<String>,
        ): PhotoMutationResult {
            val result = trashPhotos(userId, nodeUids)
            return PhotoMutationResult(result.trashedCount, result.failedCount)
        }
    }
}
