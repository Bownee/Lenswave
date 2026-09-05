package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDeletionCoordinatorTest {
    private val host = FakeHost()
    private val coordinator = GalleryDeletionCoordinator(host, NamingText())

    @Test
    fun aSelectionIsConfirmedWithItsNodesBoundToTheAccount() {
        coordinator.delete(USER, listOf(photo("p1"), photo("p2")))

        assertEquals(listOf("confirm user [n1, n2]"), host.events)
    }

    @Test
    fun anEmptySelectionAsksNothing() {
        coordinator.delete(USER, emptyList())

        assertEquals(emptyList<String>(), host.events)
    }

    @Test
    fun theConfirmationIsNotShownOverASavedStateOrAnOpenConfirmation() {
        host.stateSaved = true
        coordinator.delete(USER, listOf(photo("p1")))
        assertEquals("a tap that cannot be answered now is dropped, not queued", emptyList<String>(), host.events)

        host.stateSaved = false
        host.trashConfirmationShowing = true
        coordinator.delete(USER, listOf(photo("p1")))
        assertEquals(emptyList<String>(), host.events)
    }

    @Test
    fun aCleanTrashIsReportedWithOneShortToast() {
        coordinator.showOutcome(GalleryMutationEvent.Trashed(successfulCount = 3, failedCount = 0))

        assertEquals(listOf("short plural:${R.plurals.moved_to_proton_trash_count_result}(3)"), host.events)
    }

    @Test
    fun aPartialTrashAddsALongToastForThePhotosLeftBehind() {
        coordinator.showOutcome(GalleryMutationEvent.Trashed(successfulCount = 2, failedCount = 1))

        assertEquals(
            listOf(
                "short plural:${R.plurals.moved_to_proton_trash_count_result}(2)",
                "long plural:${R.plurals.could_not_move_count}(1)",
            ),
            host.events,
        )
    }

    @Test
    fun aFailedTrashIsOneLongToast() {
        coordinator.showOutcome(GalleryMutationEvent.TrashFailed)

        assertEquals(listOf("long string:${R.string.could_not_move_photos_to_proton_trash}"), host.events)
    }

    private fun photo(id: String) =
        GalleryAsset(
            stableId = id,
            capturedAtEpochMillis = 1L,
            nodeUid = "n${id.drop(1)}",
            hasThumbnail = true,
        )

    private class FakeHost : GalleryDeletionCoordinator.Host {
        val events = mutableListOf<String>()
        override var stateSaved = false
        override var trashConfirmationShowing = false

        override fun showTrashConfirmation(
            userId: UserId,
            nodeUids: List<String>,
        ) {
            events += "confirm ${userId.id} $nodeUids"
        }

        override fun showMessage(
            message: String,
            long: Boolean,
        ) {
            events += "${if (long) "long" else "short"} $message"
        }
    }

    /** Names the resource and its arguments instead of resolving them. */
    private class NamingText : GalleryText {
        override fun string(
            id: Int,
            vararg arguments: Any,
        ): String = "string:$id" + if (arguments.isEmpty()) "" else "(${arguments.joinToString()})"

        override fun quantity(
            id: Int,
            quantity: Int,
            vararg arguments: Any,
        ): String = "plural:$id($quantity)"
    }

    private companion object {
        val USER = UserId("user")
    }
}
