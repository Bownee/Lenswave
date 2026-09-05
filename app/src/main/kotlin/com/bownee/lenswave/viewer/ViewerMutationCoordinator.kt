package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.ProtonPhotoMutations
import com.bownee.lenswave.proton.ProtonAccountMutationForgetter
import com.bownee.lenswave.proton.ProtonSessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
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
 *
 * Every started mutation ends in exactly one queued outcome: the viewer disables its buttons on
 * start and only re-enables them on the outcome, so a call that ends any other way would leave
 * them disabled for good.
 */
@Singleton
class ViewerMutationCoordinator internal constructor(
    private val photoMutations: ProtonPhotoMutations,
    private val deletionExecutor: PhotoDeletionExecutor,
    private val scope: CoroutineScope,
) : ProtonAccountMutationForgetter {
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

    /**
     * Drops every queued outcome and every in-flight mark. For an account transition: the
     * outcomes belong to photos of the account that is going, and no viewer of the next account
     * must find its buttons held by them. A call still running ends with a session change and
     * would re-queue a failed outcome; clearing after the transition's disconnect avoids that.
     */
    override fun forgetAll() {
        pending.value = emptyList()
        inFlight.value = emptySet()
    }

    private fun run(
        stableId: String,
        mutation: suspend () -> Outcome,
    ): Boolean {
        // One atomic step claims the photo, so two callers racing here cannot both start.
        if (stableId in inFlight.getAndUpdate { running -> running + stableId }) return false
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

    /**
     * A failed call is an outcome, not an exception: the viewer reports it and moves on. A
     * session change is a cancellation subtype, but not this coroutine's own; left to propagate
     * it would end the call with no outcome at all.
     */
    private suspend fun runMutation(mutation: suspend () -> Boolean): Boolean =
        try {
            mutation()
        } catch (_: ProtonSessionChangedException) {
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
}
