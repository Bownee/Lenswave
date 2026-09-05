package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.PhotoMutationResult
import com.bownee.lenswave.proton.ProtonFavoriteResult
import com.bownee.lenswave.proton.ProtonPhotoMutations
import com.bownee.lenswave.proton.ProtonTrashResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
