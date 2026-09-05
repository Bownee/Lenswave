package com.bownee.lenswave.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryUpdatePresenterTest {
    private val dispatcher = StandardTestDispatcher()
    private val host = FakeHost()
    private val updates = FakeUpdates()

    @Test
    fun anAvailableUpdateIsTakenOverMarkedShownAndPresented() =
        runTest(dispatcher) {
            val presenter = presenter()

            presenter.checkForUpdate()
            runCurrent()
            assertEquals("awaiting the check", emptyList<String>(), host.events)

            updates.result.complete("2.0.0")
            runCurrent()

            assertEquals(listOf("mark-shown"), updates.events)
            assertEquals(listOf("show 2.0.0 over 1.0.0"), host.events)
            assertNull("shown, so nothing is carried", presenter.pendingVersionName)
        }

    @Test
    fun noUpdateLeavesTheHostAlone() =
        runTest(dispatcher) {
            val presenter = presenter()
            presenter.checkForUpdate()
            updates.result.complete(null)
            runCurrent()

            assertEquals(emptyList<String>(), host.events)
            assertEquals(emptyList<String>(), updates.events)
            assertNull(presenter.pendingVersionName)
        }

    @Test
    fun anUpdateArrivingAfterTheStateWasSavedWaitsForTheNextResume() =
        runTest(dispatcher) {
            val presenter = presenter()
            presenter.checkForUpdate()
            host.stateSaved = true
            updates.result.complete("2.0.0")
            runCurrent()

            assertEquals("marked as this activity's to show", listOf("mark-shown"), updates.events)
            assertEquals(emptyList<String>(), host.events)
            assertEquals("2.0.0", presenter.pendingVersionName)

            host.stateSaved = false
            presenter.showPendingUpdate()
            assertEquals(listOf("show 2.0.0 over 1.0.0"), host.events)
            assertNull(presenter.pendingVersionName)
        }

    @Test
    fun theVersionSurvivesARecreationAndIsDroppedWhenTheRestoredDialogAlreadyShowsIt() {
        val presenter = presenter()
        presenter.restorePendingVersion("2.0.0")
        assertEquals("2.0.0", presenter.pendingVersionName)

        host.updateDialogShowing = true
        presenter.showPendingUpdate()

        assertEquals("the fragment manager restored the dialog itself", emptyList<String>(), host.events)
        assertNull(presenter.pendingVersionName)
    }

    @Test
    fun nothingPendingShowsNothing() {
        val presenter = presenter()
        presenter.restorePendingVersion(null)
        presenter.showPendingUpdate()
        assertEquals(emptyList<String>(), host.events)
    }

    @Test
    fun theDialogsAnswersOpenTheReleasePageOrSnooze() {
        val presenter = presenter()

        presenter.onUpdateRequested()
        assertEquals(listOf("open-release-page"), host.events)

        host.canOpenReleasePage = false
        presenter.onUpdateRequested()
        assertEquals(listOf("open-release-page", "open-release-page", "no-browser"), host.events)

        presenter.onUpdateSnoozed("2.0.0")
        assertEquals(listOf("snooze 2.0.0"), updates.events)
    }

    private fun presenter() =
        GalleryUpdatePresenter(
            host = host,
            updates = updates,
            scope = CoroutineScope(dispatcher),
            currentVersionName = "1.0.0",
        )

    private class FakeHost : GalleryUpdatePresenter.Host {
        val events = mutableListOf<String>()
        override var stateSaved = false
        override var updateDialogShowing = false
        var canOpenReleasePage = true

        override fun showUpdateDialog(
            versionName: String,
            currentVersionName: String,
        ) {
            events += "show $versionName over $currentVersionName"
        }

        override fun openReleasePage(): Boolean {
            events += "open-release-page"
            return canOpenReleasePage
        }

        override fun showNoBrowserNotice() {
            events += "no-browser"
        }
    }

    private class FakeUpdates : GalleryUpdatePresenter.Updates {
        val events = mutableListOf<String>()
        val result = CompletableDeferred<String?>()

        override suspend fun awaitStartupUpdate(currentVersionName: String): String? = result.await()

        override fun markStartupUpdateShown() {
            events += "mark-shown"
        }

        override fun snooze(versionName: String) {
            events += "snooze $versionName"
        }
    }
}
