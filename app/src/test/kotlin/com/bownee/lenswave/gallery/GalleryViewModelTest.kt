package com.bownee.lenswave.gallery

import androidx.lifecycle.SavedStateHandle
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDispatchers
import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.ProtonSessionChangedException
import com.bownee.lenswave.proton.ProtonTagState
import com.bownee.lenswave.proton.ProtonThumbnailScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.AccountDetails
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every dispatcher the view model uses (Main through [Dispatchers.setMain], IO and Default
 * through [LenswaveDispatchers]) is the one test dispatcher, so state arrives exactly when the
 * test advances the scheduler and nothing runs on a real thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = mutableListOf<String>()
    private val reader = FakeReader(events)
    private val scheduler = FakeScheduler(events)
    private val accountManager = FakeAccountManager(events)
    private val session = MutableStateFlow(ProtonAccountSessionState())
    private val navigationStore = FakeNavigationStore()
    private val deletionExecutor = FakeDeletionExecutor(events)
    private val savedState = SavedStateHandle()
    private val text = CountingText()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the initial state is a skeleton of the restored destination and nothing is mapped until someone collects`() =
        runTest(dispatcher) {
            savedState["gallery.destination"] = "proton-tag"
            savedState["gallery.proton-tag"] = ProtonMediaTag.VIDEOS.name
            reader.state.value = loadedTimeline("p1", tags = mapOf(ProtonMediaTag.VIDEOS to loadedTag("p1")))

            val viewModel = viewModel()
            val initial = viewModel.uiState.value

            assertEquals(GalleryDestination.Tag(ProtonMediaTag.VIDEOS), initial.destination)
            assertEquals("${R.string.proton_tag_videos}()", initial.title)
            assertTrue(initial.visibleAssets.isEmpty())
            assertNull(initial.emptyState)
            assertEquals("only the skeleton's title may be resolved on construction", 1, text.calls)

            runCurrent()
            reader.state.value =
                loadedTimeline("p1", "p2", tags = mapOf(ProtonMediaTag.VIDEOS to loadedTag("p1", "p2")))
            runCurrent()

            assertSame("no collector, no mapping", initial, viewModel.uiState.value)
            assertEquals(1, text.calls)

            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(
                listOf("p1", "p2"),
                viewModel.uiState.value.visibleAssets
                    .map(GalleryAsset::nodeUid),
            )
            assertTrue(text.calls > 1)
        }

    @Test
    fun `the state stops recomputing once the last collector has been gone for a while`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel(collect = false)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            collector.cancel()
            advanceTimeBy(5_001L)
            runCurrent()
            val callsBefore = text.calls
            reader.state.value = loadedTimeline("p1", "p2")
            runCurrent()

            assertEquals(callsBefore, text.calls)
            assertEquals(
                listOf("p1"),
                viewModel.uiState.value.visibleAssets
                    .map(GalleryAsset::nodeUid),
            )
        }

    @Test
    fun `selectDestination publishes the destination, stores it and refreshes quietly`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()

            viewModel.selectDestination(GalleryDestination.Library)
            runCurrent()

            assertEquals(GalleryDestination.Library, viewModel.uiState.value.destination)
            assertFalse(viewModel.uiState.value.isRefreshing)
            assertEquals(listOf("syncAlbums:u:false", "enqueue:u"), events)
            assertEquals("library", savedState.get<String>("gallery.destination"))
            assertEquals(GalleryDestination.Library, navigationStore.stored)

            events.clear()
            viewModel.selectDestination(GalleryDestination.Library)
            runCurrent()

            assertTrue("re-selecting the current destination is a no-op: $events", events.isEmpty())
        }

    @Test
    fun `openAlbum publishes the album once its cached photos are in place`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            reader.cachedAlbumPhotos = listOf(photo("al-1", captureTime = 2L), photo("al-2", captureTime = 1L))
            val states = mutableListOf<GalleryUiState>()
            backgroundScope.launch { viewModel.uiState.collect { states += it } }
            runCurrent()
            states.clear()

            viewModel.openAlbum(album("al"))
            runCurrent()

            val albumStates =
                states.filter { state ->
                    state.destination ==
                        GalleryDestination.AlbumPhotos(album("al").reference())
                }
            assertEquals("one publish, with the cached photos already in it", 1, albumStates.size)
            assertEquals(listOf("al-1", "al-2"), albumStates.single().visibleAssets.map(GalleryAsset::nodeUid))
            assertEquals(
                listOf("loadCachedAlbum:al", "enqueue:u", "syncAlbumPhotos:al:false", "enqueue:u"),
                events,
            )
            assertEquals("al", savedState.get<String>("gallery.album-uid"))
            // A cold start reopens the tab root, never the album.
            assertEquals(GalleryDestination.Library, navigationStore.stored)
        }

    @Test
    fun `the current page's scroll position is saved to the state and seeds a restored view model`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()

            viewModel.saveScrollPosition(GalleryDestination.Library, GalleryScrollPosition(8, -3))
            viewModel.saveScrollPosition(GalleryDestination.Timeline, GalleryScrollPosition(42, -17))

            assertEquals(
                GalleryScrollPosition(8, -3),
                viewModel.scrollPositions.positionFor(GalleryDestination.Library),
            )
            assertEquals(42, savedState.get<Int>("gallery.scroll-first-visible"))
            assertEquals(-17, savedState.get<Int>("gallery.scroll-top-offset"))

            viewModel.selectDestination(GalleryDestination.Library)
            runCurrent()
            assertEquals(
                "the saved state follows the destination",
                8,
                savedState.get<Int>("gallery.scroll-first-visible"),
            )

            viewModel.selectDestination(GalleryDestination.Tag(ProtonMediaTag.VIDEOS))
            runCurrent()
            assertNull(
                "a page never scrolled has no saved position",
                savedState.get<Int>("gallery.scroll-first-visible"),
            )

            savedState["gallery.destination"] = "proton-timeline"
            savedState["gallery.scroll-first-visible"] = 42
            savedState["gallery.scroll-top-offset"] = -17
            val restored = viewModel()

            assertEquals(
                GalleryScrollPosition(42, -17),
                restored.scrollPositions.positionFor(GalleryDestination.Timeline),
            )
            assertNull(restored.scrollPositions.positionFor(GalleryDestination.Library))
        }

    @Test
    fun `the selection is saved to the state and cleared by a navigation`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()

            viewModel.setSelection(setOf("p1", "p2"))

            assertEquals(setOf("p1", "p2"), viewModel.selectedStableIds)
            assertEquals(listOf("p1", "p2"), savedState.get<ArrayList<String>>("gallery.selection"))
            assertEquals(setOf("p1", "p2"), viewModel().selectedStableIds)

            viewModel.selectDestination(GalleryDestination.Library)
            runCurrent()

            assertTrue(viewModel.selectedStableIds.isEmpty())
            assertTrue(savedState.get<ArrayList<String>>("gallery.selection").orEmpty().isEmpty())
        }

    @Test
    fun `trashPhotos runs once at a time, clears the selection and reports the outcome to a late collector`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            viewModel.setSelection(setOf("p1"))

            viewModel.trashPhotos(listOf("p1"))
            viewModel.trashPhotos(listOf("p2"))
            runCurrent()

            assertEquals("a second call while one is in flight is dropped", listOf("trash:u:p1"), events)
            deletionExecutor.result = PhotoMutationResult(successfulCount = 1, failedCount = 1)
            deletionExecutor.held.single().complete(Unit)
            runCurrent()

            assertTrue(viewModel.selectedStableIds.isEmpty())
            // The activity that started the call may be gone; the next one still hears the outcome.
            val received = mutableListOf<GalleryMutationEvent>()
            backgroundScope.launch { viewModel.mutationEvents.collect { received += it } }
            runCurrent()
            assertEquals(listOf(GalleryMutationEvent.Trashed(successfulCount = 1, failedCount = 1)), received)

            deletionExecutor.failure = IllegalStateException("offline")
            viewModel.trashPhotos(listOf("p2"))
            runCurrent()
            deletionExecutor.held.last().complete(Unit)
            runCurrent()

            assertEquals(GalleryMutationEvent.TrashFailed, received.last())
        }

    @Test
    fun `a trash cancelled by an account change is reported as failed and frees the next trash`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            val received = mutableListOf<GalleryMutationEvent>()
            backgroundScope.launch { viewModel.mutationEvents.collect { received += it } }
            viewModel.setSelection(setOf("p1"))
            deletionExecutor.failure = ProtonSessionChangedException()

            viewModel.trashPhotos(listOf("p1"))
            runCurrent()
            deletionExecutor.held.single().complete(Unit)
            runCurrent()

            assertEquals(listOf<GalleryMutationEvent>(GalleryMutationEvent.TrashFailed), received)
            assertEquals(
                "the photos were not trashed, so the selection stands",
                setOf("p1"),
                viewModel.selectedStableIds,
            )

            deletionExecutor.failure = null
            viewModel.trashPhotos(listOf("p1"))
            runCurrent()

            assertEquals("the in-flight guard was released", listOf("trash:u:p1", "trash:u:p1"), events)
        }

    @Test
    fun `a trash confirmed before the restored session has settled runs once the account arrives`() =
        runTest(dispatcher) {
            reader.state.value = loadedTimeline("p1")
            reader.albumsState.value = ProtonAlbumsState(userId = USER.id, hasLoaded = true)
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            val received = mutableListOf<GalleryMutationEvent>()
            backgroundScope.launch { viewModel.mutationEvents.collect { received += it } }
            runCurrent()

            // The confirmation dialog restored by the fragment manager answers before the session flow does.
            viewModel.trashPhotos(listOf("p1"))
            runCurrent()
            assertTrue("nothing is trashed without an account", events.none { it.startsWith("trash:") })
            assertTrue(received.isEmpty())

            session.value =
                ProtonAccountSessionState(readyAccount(USER), USER, initialized = true, transitioning = true)
            runCurrent()
            assertTrue("a transitioning session is not an account yet", events.none { it.startsWith("trash:") })

            session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)
            runCurrent()
            assertEquals(listOf("trash:u:p1"), events.filter { it.startsWith("trash:") })
            deletionExecutor.held.single().complete(Unit)
            runCurrent()

            assertEquals(
                listOf<GalleryMutationEvent>(GalleryMutationEvent.Trashed(successfulCount = 1, failedCount = 0)),
                received,
            )
        }

    @Test
    fun `a trash confirmed while no session ever arrives is reported as failed`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val received = mutableListOf<GalleryMutationEvent>()
            backgroundScope.launch { viewModel.mutationEvents.collect { received += it } }
            viewModel.setSelection(setOf("p1"))

            viewModel.trashPhotos(listOf("p1"))
            advanceTimeBy(9_999L)
            runCurrent()
            assertTrue(received.isEmpty())

            advanceTimeBy(2L)
            runCurrent()

            assertEquals(listOf<GalleryMutationEvent>(GalleryMutationEvent.TrashFailed), received)
            assertTrue(events.none { it.startsWith("trash:") })
            assertEquals(
                "the photos were not trashed, so the selection stands",
                setOf("p1"),
                viewModel.selectedStableIds,
            )

            // A session that settles without an account fails the same way, and the guard is free again.
            session.value = ProtonAccountSessionState(initialized = true)
            viewModel.trashPhotos(listOf("p1"))
            runCurrent()
            assertEquals(2, received.size)
            assertEquals(GalleryMutationEvent.TrashFailed, received.last())
        }

    @Test
    fun `a manual refresh stays refreshing until the latest manual refresh has finished`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            reader.holdTimelineSyncs = true

            viewModel.requestRefresh()
            runCurrent()

            assertTrue(viewModel.uiState.value.isRefreshing)
            assertEquals(listOf("syncTimeline:u:true"), events)

            viewModel.requestRefresh()
            runCurrent()
            reader.heldTimelineSyncs[0].complete(Unit)
            runCurrent()

            assertTrue(
                "the first refresh ending must not clear the second one's spinner",
                viewModel.uiState.value.isRefreshing,
            )

            reader.heldTimelineSyncs[1].complete(Unit)
            runCurrent()

            assertFalse(viewModel.uiState.value.isRefreshing)
            assertEquals(listOf("syncTimeline:u:true", "syncTimeline:u:true", "enqueue:u", "enqueue:u"), events)

            events.clear()
            viewModel.refreshAfterMutation()
            runCurrent()

            assertFalse("a quiet refresh never shows the spinner", viewModel.uiState.value.isRefreshing)
            assertEquals(listOf("syncTimeline:u:false"), events)
        }

    @Test
    fun `disconnectProton removes the account and returns a collection to its tab root`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            viewModel.openAlbum(album("al"))
            runCurrent()
            events.clear()

            viewModel.disconnectProton()
            runCurrent()

            assertEquals(listOf("removeAccount:u"), events)
            assertEquals(GalleryDestination.Library, viewModel.uiState.value.destination)
            assertEquals("library", savedState.get<String>("gallery.destination"))
        }

    @Test
    fun `an account removed underneath the app is reported as signed out, an explicit disconnect is not`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()

            session.value = ProtonAccountSessionState(initialized = true)
            runCurrent()

            assertEquals(
                "${R.string.signed_out}()",
                viewModel.uiState.value.emptyState
                    ?.title,
            )

            session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)
            runCurrent()
            assertNull(viewModel.uiState.value.emptyState)

            viewModel.disconnectProton()
            runCurrent()
            session.value = ProtonAccountSessionState(initialized = true)
            runCurrent()

            assertEquals(
                "${R.string.connect_proton_photos}()",
                viewModel.uiState.value.emptyState
                    ?.title,
            )
        }

    @Test
    fun `a transitioning session publishes connecting without refreshing`() =
        runTest(dispatcher) {
            // Without a cached timeline, connected shows nothing yet while connecting shows the loading panel.
            val viewModel = connectedViewModel(timeline = ProtonGalleryState())
            assertNull(viewModel.uiState.value.emptyState)
            assertTrue(viewModel.uiState.value.isProtonConnected)

            session.value =
                ProtonAccountSessionState(
                    account = readyAccount(USER),
                    activeUserId = null,
                    initialized = true,
                    transitioning = true,
                )
            runCurrent()

            val panel =
                viewModel.uiState.value.emptyState
                    ?.title
                    .orEmpty()
            assertTrue(panel, panel.startsWith(R.string.loading_metadata.toString()))
            assertFalse(viewModel.uiState.value.isProtonConnected)
            assertTrue("a transition must not start a refresh: $events", events.isEmpty())
        }

    @Test
    fun `a new account with cached metadata restarts the thumbnail scheduler and refreshes`() =
        runTest(dispatcher) {
            reader.state.value = loadedTimeline("p1")
            reader.albumsState.value = ProtonAlbumsState(userId = USER.id, hasLoaded = true)
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)
            runCurrent()

            assertEquals(listOf("restart:u", "syncTimeline:u:false", "enqueue:u"), events)
            assertTrue(viewModel.uiState.value.isProtonConnected)
        }

    @Test
    fun `a new account without cached metadata fetches it before the scheduler restarts`() =
        runTest(dispatcher) {
            savedState["gallery.destination"] = "proton-tag"
            savedState["gallery.proton-tag"] = ProtonMediaTag.VIDEOS.name
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)
            runCurrent()

            assertEquals(
                listOf("syncTimeline:u:false", "syncAlbums:u:false", "syncTag:VIDEOS:false", "restart:u"),
                events,
            )
        }

    @Test
    fun `a restored album destination loads the cached album before syncing it`() =
        runTest(dispatcher) {
            savedState["gallery.destination"] = "proton-album"
            savedState["gallery.album-uid"] = "al"
            savedState["gallery.album-name"] = "Album al"
            reader.cachedAlbumPhotos = listOf(photo("al-1", captureTime = 1L))

            // Without any cached metadata (a cold start into the album).
            val cold = viewModel()
            backgroundScope.launch { cold.uiState.collect {} }
            runCurrent()
            session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)
            runCurrent()

            assertEquals(
                listOf(
                    "syncTimeline:u:false",
                    "syncAlbums:u:false",
                    "loadCachedAlbum:al",
                    "syncAlbumPhotos:al:false",
                    "restart:u",
                ),
                events,
            )
            assertEquals(
                listOf("al-1"),
                cold.uiState.value.visibleAssets
                    .map(GalleryAsset::nodeUid),
            )

            // With the metadata loaded (a recreation): the startup refresh loads the cached album first.
            events.clear()
            reader.state.value = loadedTimeline("p1")
            reader.albumsState.value =
                ProtonAlbumsState(userId = USER.id, albums = listOf(album("al")), hasLoaded = true)
            reader.albumPhotosState.value = ProtonAlbumPhotosState()
            val recreated = viewModel()
            backgroundScope.launch { recreated.uiState.collect {} }
            runCurrent()

            assertEquals(
                listOf("restart:u", "loadCachedAlbum:al", "syncAlbumPhotos:al:false", "enqueue:u"),
                events,
            )

            // A later refresh finds the album loaded and does not read the cache again.
            events.clear()
            recreated.requestRefresh(manual = false)
            runCurrent()

            assertEquals(listOf("syncAlbumPhotos:al:false", "enqueue:u"), events)
        }

    @Test
    fun `a restored album that the loaded album list no longer has returns to the album list`() =
        runTest(dispatcher) {
            savedState["gallery.destination"] = "proton-album"
            savedState["gallery.album-uid"] = "gone"
            savedState["gallery.album-name"] = "Album gone"
            reader.state.value = loadedTimeline("p1")
            reader.albumsState.value =
                ProtonAlbumsState(userId = USER.id, albums = listOf(album("al")), hasLoaded = true)
            session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)

            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(GalleryDestination.Library, viewModel.uiState.value.destination)
            assertEquals("library", savedState.get<String>("gallery.destination"))

            // An album that is still listed stays, and stays while the list is merely reloading.
            viewModel.openAlbum(album("al"))
            runCurrent()
            reader.albumsState.value = ProtonAlbumsState(userId = USER.id, albums = emptyList(), hasLoaded = false)
            runCurrent()
            assertEquals(GalleryDestination.AlbumPhotos(album("al").reference()), viewModel.uiState.value.destination)

            reader.albumsState.value = ProtonAlbumsState(userId = USER.id, albums = emptyList(), hasLoaded = true)
            runCurrent()
            assertEquals(GalleryDestination.Library, viewModel.uiState.value.destination)
        }

    @Test
    fun `runPeriodicSync checks at the policy interval and skips a transitioning session`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            val periodic = backgroundScope.launch { viewModel.runPeriodicSync() }
            runCurrent()

            assertTrue(
                "the start-up refresh just completed, so the immediate check does nothing: $events",
                events.isEmpty(),
            )

            advanceTimeBy(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS - 1L)
            runCurrent()
            assertTrue(events.isEmpty())
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("syncTimeline:u:false", "enqueue:u"), events)

            // A user-driven refresh shortly before a tick leaves the listing fresh: the tick does nothing.
            events.clear()
            advanceTimeBy(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS - 60_000L)
            runCurrent()
            viewModel.refreshAfterMutation()
            runCurrent()
            assertEquals(listOf("syncTimeline:u:false", "enqueue:u"), events)
            events.clear()
            advanceTimeBy(60_000L)
            runCurrent()
            assertTrue("a tick within the freshness limit of a refresh is skipped: $events", events.isEmpty())

            // The next tick finds that refresh a freshness limit old and enumerates again.
            advanceTimeBy(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS)
            runCurrent()
            assertEquals(listOf("syncTimeline:u:false", "enqueue:u"), events)

            session.value =
                ProtonAccountSessionState(readyAccount(USER), USER, initialized = true, transitioning = true)
            runCurrent()
            events.clear()
            advanceTimeBy(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS)
            runCurrent()

            assertTrue("no check while the session transitions: $events", events.isEmpty())
            periodic.cancel()
        }

    @Test
    fun `a refresh the repository published as failed does not count as completed for the periodic sync`() =
        runTest(dispatcher) {
            val viewModel = connectedViewModel()
            val periodic = backgroundScope.launch { viewModel.runPeriodicSync() }
            runCurrent()

            // A user-driven refresh shortly before a tick fails offline; the repository swallows the
            // failure and publishes refreshFailed instead of a fresh listing.
            advanceTimeBy(GalleryPeriodicSyncPolicy.CHECK_INTERVAL_MILLIS - 60_000L)
            reader.failTimelineSyncs = true
            viewModel.refreshAfterMutation()
            runCurrent()
            assertEquals(listOf("syncTimeline:u:false", "enqueue:u"), events)
            events.clear()

            // The tick that follows is not skipped as if the listing had been refreshed.
            reader.failTimelineSyncs = false
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(listOf("syncTimeline:u:false", "enqueue:u"), events)
            periodic.cancel()
        }

    private fun viewModel() =
        GalleryViewModel(
            galleryText = text,
            accountManager = accountManager,
            protonRepository = reader,
            protonThumbnailScheduler = scheduler,
            accountSession = session,
            navigationStore = navigationStore,
            deletionExecutor = deletionExecutor,
            savedStateHandle = savedState,
            clock = SchedulerClock(),
            dispatchers = TestDispatchers(),
        )

    /**
     * A view model with a ready account, cached albums and [timeline], collected unless [collect]
     * is false; the events of its start-up are discarded.
     */
    private fun TestScope.connectedViewModel(
        timeline: ProtonGalleryState = loadedTimeline("p1"),
        collect: Boolean = true,
    ): GalleryViewModel {
        reader.state.value = timeline
        reader.albumsState.value = ProtonAlbumsState(userId = USER.id, hasLoaded = true)
        session.value = ProtonAccountSessionState(readyAccount(USER), USER, initialized = true)
        val viewModel = viewModel()
        if (collect) backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        events.clear()
        return viewModel
    }

    /** Photos listed newest first, as the repositories publish them. */
    private fun loadedTimeline(
        vararg nodeUids: String,
        tags: Map<ProtonMediaTag, ProtonTagState> = emptyMap(),
    ) = ProtonGalleryState(userId = USER.id, photos = photos(nodeUids), hasLoaded = true, tags = tags)

    private fun loadedTag(vararg nodeUids: String) = ProtonTagState(photos = photos(nodeUids), hasLoaded = true)

    private fun photos(nodeUids: Array<out String>) =
        nodeUids.mapIndexed { index, nodeUid -> photo(nodeUid, captureTime = (nodeUids.size - index).toLong()) }

    private fun photo(
        nodeUid: String,
        captureTime: Long,
    ) = ProtonGalleryPhoto(nodeUid = nodeUid, captureTimeEpochSeconds = captureTime, hasThumbnail = true)

    private fun album(nodeUid: String) =
        ProtonAlbum(
            nodeUid = nodeUid,
            name = "Album $nodeUid",
            photoCount = 2L,
            coverPhotoNodeUid = null,
            createdAtEpochSeconds = 0L,
            lastActivityEpochSeconds = 0L,
            hasCoverThumbnail = false,
            isShared = false,
        )

    private fun readyAccount(userId: UserId) =
        Account(userId, "user", null, AccountState.Ready, null, null, AccountDetails(null, null))

    private inner class SchedulerClock : LenswaveClock {
        override fun nowMillis(): Long = dispatcher.scheduler.currentTime
    }

    private inner class TestDispatchers : LenswaveDispatchers {
        override val io = dispatcher
        override val default = dispatcher
    }

    private class CountingText : GalleryText {
        var calls = 0

        override fun string(
            id: Int,
            vararg arguments: Any,
        ): String {
            calls++
            return "$id(${arguments.joinToString()})"
        }

        override fun quantity(
            id: Int,
            quantity: Int,
            vararg arguments: Any,
        ): String {
            calls++
            return quantity.toString()
        }
    }

    private class FakeReader(
        private val events: MutableList<String>,
    ) : ProtonGalleryReader {
        override val state = MutableStateFlow(ProtonGalleryState())
        override val albumsState = MutableStateFlow(ProtonAlbumsState())
        override val albumPhotosState = MutableStateFlow(ProtonAlbumPhotosState())
        var cachedAlbumPhotos: List<ProtonGalleryPhoto> = emptyList()
        var holdTimelineSyncs = false
        var failTimelineSyncs = false
        val heldTimelineSyncs = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun syncTimelineMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) {
            events += "syncTimeline:${userId.id}:$forceRemote"
            state.value = state.value.copy(refreshFailed = failTimelineSyncs)
            if (holdTimelineSyncs) CompletableDeferred<Unit>().also(heldTimelineSyncs::add).await()
        }

        override suspend fun syncTagMetadata(
            userId: UserId,
            tag: ProtonMediaTag,
            forceRemote: Boolean,
        ) {
            events += "syncTag:${tag.name}:$forceRemote"
        }

        override suspend fun syncAlbumsMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) {
            events += "syncAlbums:${userId.id}:$forceRemote"
        }

        override suspend fun loadCachedAlbum(
            userId: UserId,
            album: ProtonAlbumReference,
        ) {
            events += "loadCachedAlbum:${album.nodeUid}"
            albumPhotosState.value =
                ProtonAlbumPhotosState(
                    userId = userId.id,
                    albumUid = album.nodeUid,
                    albumName = album.name,
                    photos = cachedAlbumPhotos,
                    hasLoaded = true,
                )
        }

        override suspend fun syncAlbumPhotoMetadata(
            userId: UserId,
            album: ProtonAlbumReference,
            forceRemote: Boolean,
        ) {
            events += "syncAlbumPhotos:${album.nodeUid}:$forceRemote"
        }
    }

    private class FakeScheduler(
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

    private class FakeDeletionExecutor(
        private val events: MutableList<String>,
    ) : PhotoDeletionExecutor {
        var result = PhotoMutationResult(successfulCount = 1, failedCount = 0)
        var failure: Throwable? = null
        val held = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun trashProton(
            userId: UserId,
            nodeUids: Collection<String>,
        ): PhotoMutationResult {
            events += "trash:${userId.id}:${nodeUids.joinToString(",")}"
            CompletableDeferred<Unit>().also(held::add).await()
            failure?.let { throw it }
            return result
        }
    }

    private class FakeNavigationStore : GalleryNavigationStore {
        var stored: GalleryDestination? = null

        override fun read(): GalleryDestination? = stored

        override fun write(destination: GalleryDestination) {
            stored = destination
        }
    }

    /** Only [removeAccount] is expected; the view model observes the session through its own flow. */
    private class FakeAccountManager(
        private val events: MutableList<String>,
    ) : AccountManager(Product.Drive) {
        override suspend fun addAccount(
            account: Account,
            session: Session,
        ) = error("unexpected")

        override suspend fun removeAccount(
            userId: UserId,
            waitForCompletion: Boolean,
        ) {
            events += "removeAccount:${userId.id}"
        }

        override suspend fun disableAccount(
            userId: UserId,
            waitForCompletion: Boolean,
            keepSession: Boolean,
        ) = error("unexpected")

        override fun getAccount(userId: UserId): Flow<Account?> = flowOf(null)

        override fun getAccounts(): Flow<List<Account>> = flowOf(emptyList())

        override fun getSessions(): Flow<List<Session>> = flowOf(emptyList())

        override fun onAccountStateChanged(initialState: Boolean): Flow<Account> = flowOf()

        override fun onSessionStateChanged(initialState: Boolean): Flow<Account> = flowOf()

        override fun getPrimaryUserId(): Flow<UserId?> = flowOf(null)

        override suspend fun getPreviousPrimaryUserId(): UserId? = null

        override suspend fun setAsPrimary(userId: UserId) = error("unexpected")
    }

    private companion object {
        val USER = UserId("u")
    }
}
