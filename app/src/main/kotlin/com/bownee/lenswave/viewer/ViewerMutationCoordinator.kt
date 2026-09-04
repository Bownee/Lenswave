package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.ProtonPhotoMutations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the viewer's favourite and trash calls in an application-wide scope, so a rotation while
 * one is in flight neither cancels the request after the server has acted on it nor loses its
 * result. Outcomes wait in [outcomes] until the viewer on screen has [consume]d them, and
 * [isInFlight] lets a recreated viewer show the pending state of the photo it reopens.
 */
@Singleton
class ViewerMutationCoordinator internal constructor(
    private val photoMutations: ProtonPhotoMutations,
    private val deletionExecutor: PhotoDeletionExecutor,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        photoMutations: ProtonPhotoMutations,
        deletionExecutor: PhotoDeletionExecutor,
    ) : this(photoMutations, deletionExecutor, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    internal sealed interface Outcome {
        val stableId: String

        data class FavoriteSet(
            override val stableId: String,
            val favorite: Boolean,
            val succeeded: Boolean,
        ) : Outcome

        data class Trashed(
            override val stableId: String,
            val succeeded: Boolean,
        ) : Outcome
    }

    private val inFlight = MutableStateFlow<Set<String>>(emptySet())
    private val pending = MutableStateFlow<List<Outcome>>(emptyList())

    /** Outcomes nobody has consumed yet, oldest first. */
    internal val outcomes: StateFlow<List<Outcome>> = pending.asStateFlow()

    internal fun isInFlight(stableId: String): Boolean = stableId in inFlight.value

    /** Starts the favourite change; false when a mutation of that photo is already running. */
    internal fun setFavorite(
        userId: UserId,
        request: PhotoRequest,
        favorite: Boolean,
    ): Boolean =
        run(request.stableId) {
            val succeeded =
                runMutation { photoMutations.setFavorite(userId, listOf(request.nodeUid), favorite).updatedCount == 1 }
            Outcome.FavoriteSet(request.stableId, favorite, succeeded)
        }

    /** Starts the move to Proton Trash; false when a mutation of that photo is already running. */
    internal fun trash(
        userId: UserId,
        request: PhotoRequest,
    ): Boolean =
        run(request.stableId) {
            val succeeded =
                runMutation { deletionExecutor.trashProton(userId, listOf(request.nodeUid)).successfulCount == 1 }
            Outcome.Trashed(request.stableId, succeeded)
        }

    internal fun consume(outcome: Outcome) {
        pending.update { queued -> queued - outcome }
    }

    private fun run(
        stableId: String,
        mutation: suspend () -> Outcome,
    ): Boolean {
        if (stableId in inFlight.value) return false
        inFlight.update { running -> running + stableId }
        scope.launch {
            try {
                val outcome = mutation()
                pending.update { queued -> queued + outcome }
            } finally {
                inFlight.update { running -> running - stableId }
            }
        }
        return true
    }

    /** A failed call is an outcome, not an exception: the viewer reports it and moves on. */
    private suspend fun runMutation(mutation: suspend () -> Boolean): Boolean =
        try {
            mutation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
}
