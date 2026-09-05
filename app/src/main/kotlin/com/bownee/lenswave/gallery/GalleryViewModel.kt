package com.bownee.lenswave.gallery

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDispatchers
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonSessionChangedException
import com.bownee.lenswave.proton.ProtonThumbnailScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.isReady
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/** What a mutation run by [GalleryViewModel] came to; the activity on screen reports it once. */
internal sealed interface GalleryMutationEvent {
    /** The outcome of a move to Proton Trash. */
    sealed interface Trash : GalleryMutationEvent

    data class Trashed(
        val successfulCount: Int,
        val failedCount: Int,
    ) : Trash

    data object TrashFailed : Trash

    /** Whether the telemetry preference from the privacy dialog reached Proton. */
    data class TelemetryPreferenceSaved(
        val saved: Boolean,
    ) : GalleryMutationEvent
}

@HiltViewModel
class GalleryViewModel internal constructor(
    galleryText: GalleryText,
    private val accountManager: AccountManager,
    private val protonRepository: ProtonGalleryReader,
    private val protonThumbnailScheduler: ProtonThumbnailScheduler,
    private val accountSession: StateFlow<ProtonAccountSessionState>,
    private val navigationStore: GalleryNavigationStore,
    private val deletionExecutor: PhotoDeletionExecutor,
    private val telemetryWriter: TelemetryPreferenceWriter,
    private val savedStateHandle: SavedStateHandle,
    private val clock: LenswaveClock,
    private val dispatchers: LenswaveDispatchers,
) : ViewModel() {
    /** The production wiring; tests use the primary constructor with plain JVM collaborators. */
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        accountManager: AccountManager,
        protonRepository: ProtonGalleryReader,
        protonThumbnailScheduler: ProtonThumbnailScheduler,
        accountSessionManager: ProtonAccountSessionManager,
        navigationStore: GalleryNavigationStore,
        deletionExecutor: PhotoDeletionExecutor,
        telemetryWriter: TelemetryPreferenceWriter,
        savedStateHandle: SavedStateHandle,
        clock: LenswaveClock,
        dispatchers: LenswaveDispatchers,
    ) : this(
        galleryText = AndroidGalleryText(context.resources),
        accountManager = accountManager,
        protonRepository = protonRepository,
        protonThumbnailScheduler = protonThumbnailScheduler,
        accountSession = accountSessionManager.state,
        navigationStore = navigationStore,
        deletionExecutor = deletionExecutor,
        telemetryWriter = telemetryWriter,
        savedStateHandle = savedStateHandle,
        clock = clock,
        dispatchers = dispatchers,
    )

    private val uiStateFactory = GalleryUiStateFactory(galleryText)

    // The stored destination is needed for the initial state, so it is read here on the main
    // thread; GalleryPreferenceWarmUp starts the file load at process start, and this read
    // waits only for whatever part of it is still outstanding.
    private var destination =
        restoreNavigation(savedStateHandle)
            ?: navigationStore.read()
            ?: GalleryDestination.Timeline
    private var currentUserId: UserId? = null
    private var sessionTransitioning = false

    /** Set by [disconnectProton], so the removal that follows is not reported as a lost session. */
    private var explicitDisconnect = false
    private var signedOut = false
    private var manualRefreshGeneration = 0
    private var lastPeriodicCheckMillis: Long? = null

    /** When the last refresh of the page on screen completed; every navigation refreshes, so it follows the page. */
    private var lastRefreshCompletedMillis: Long? = null

    /**
     * Where each page was scrolled to. Owned here so a configuration change keeps every page's
     * position; the current page's position is also written to the saved state, so a process
     * death restores the page the user was looking at where they left it.
     */
    internal val scrollPositions = GalleryScrollPositionStore()

    /**
     * The stable ids of the selected photos. Owned here and written to the saved state, so a
     * selection survives the activity's recreation; the activity re-applies it to the grid once
     * the page's rows are on screen. A selection never outlives the page it was made on.
     */
    internal var selectedStableIds: Set<String> =
        savedStateHandle.get<ArrayList<String>>(STATE_SELECTION)?.toSet().orEmpty()
        private set

    private var mutationInFlight = false

    // A channel, not a shared flow: an outcome that arrives while no activity collects must wait
    // for the next one rather than be dropped.
    private val mutableMutationEvents =
        Channel<GalleryMutationEvent>(capacity = MUTATION_EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Outcomes of the photo mutations run here, for the activity on screen to report once. */
    internal val mutationEvents: Flow<GalleryMutationEvent> = mutableMutationEvents.receiveAsFlow()

    /**
     * Everything the UI state depends on that is owned by this view model. It joins the
     * repository flows below; a change to any of them recomputes the state once, off the
     * main thread, and bursts of changes collapse into the latest one.
     */
    private val localInputs =
        MutableStateFlow(
            LocalInputs(
                destination = destination,
                currentUserId = null,
                accountStatus = accountStatus(ProtonAccountSessionState()),
                isRefreshing = false,
            ),
        )

    /**
     * The initial value is a skeleton because it is built on whichever thread first touches
     * this property (the main thread in the activity's onCreate); mapping and sorting a warm
     * repository's timeline there would delay the first frame. The full state follows from
     * the Default dispatcher. Sharing stops shortly after the last collector leaves, so the
     * join does not recompute states while the gallery is off screen.
     */
    val uiState: StateFlow<GalleryUiState> =
        combine(
            protonRepository.state,
            protonRepository.albumsState,
            protonRepository.albumPhotosState,
            localInputs,
            ::uiInputs,
        ).conflate()
            .map(uiStateFactory::create)
            .flowOn(dispatchers.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = UI_STATE_STOP_TIMEOUT_MILLIS),
                initialValue =
                    uiStateFactory.skeleton(
                        uiInputs(
                            protonRepository.state.value,
                            protonRepository.albumsState.value,
                            protonRepository.albumPhotosState.value,
                            localInputs.value,
                        ),
                    ),
            )

    init {
        restoreScrollPosition(savedStateHandle)?.let { position -> scrollPositions.save(destination, position) }
        observeAccountSession()
        observeAlbums()
    }

    /** Records where [pageDestination] is scrolled to; the current page's position also goes to the saved state. */
    internal fun saveScrollPosition(
        pageDestination: GalleryDestination,
        position: GalleryScrollPosition,
    ) {
        scrollPositions.save(pageDestination, position)
        if (pageDestination == destination) persistScrollPosition()
    }

    fun selectDestination(newDestination: GalleryDestination) {
        if (destination == newDestination) return
        destination = newDestination
        saveDestination()
        publishUiState()
        requestRefresh(manual = false)
    }

    fun navigateUp() {
        GalleryNavigationPolicy.parent(destination)?.let(::selectDestination)
    }

    /**
     * The cached album is loaded before the destination is published, so the screen goes
     * straight from the album list to the album's photos in one render instead of showing an
     * empty page first and rendering again when the cache arrives.
     */
    fun openAlbum(album: ProtonAlbum) {
        val albumReference = album.reference()
        destination = GalleryDestination.AlbumPhotos(albumReference)
        saveDestination()
        viewModelScope.launch {
            val userId = currentUserId
            try {
                if (userId != null) {
                    withContext(dispatchers.io) { protonRepository.loadCachedAlbum(userId, albumReference) }
                }
            } finally {
                // Whatever the cache said, the navigation must land; a later change wins.
                publishUiState()
            }
            if (userId != null) protonThumbnailScheduler.enqueue(userId)
            requestRefresh(manual = false)
        }
    }

    internal fun setSelection(stableIds: Set<String>) {
        if (selectedStableIds == stableIds) return
        selectedStableIds = stableIds
        savedStateHandle[STATE_SELECTION] = ArrayList(stableIds)
    }

    /**
     * Moves the photos to Proton Trash. Runs in this scope, so the call is neither cancelled nor
     * left unreported when the activity is recreated while it is in flight; the outcome reaches
     * whichever activity collects [mutationEvents] next.
     *
     * The confirmation dialog survives a process death and may answer before the restored
     * session has settled; the call then waits for the account (bounded, see
     * [awaitSessionUserId]) rather than dropping a confirmed destructive action without a word.
     */
    fun trashPhotos(nodeUids: List<String>) {
        if (mutationInFlight || nodeUids.isEmpty()) return
        mutationInFlight = true
        viewModelScope.launch {
            try {
                val userId = currentUserId ?: awaitSessionUserId()
                if (userId == null) {
                    mutableMutationEvents.trySend(GalleryMutationEvent.TrashFailed)
                } else {
                    val result = deletionExecutor.trashProton(userId, nodeUids)
                    setSelection(emptySet())
                    mutableMutationEvents.trySend(
                        GalleryMutationEvent.Trashed(result.successfulCount, result.failedCount),
                    )
                }
            } catch (_: ProtonSessionChangedException) {
                // A CancellationException subtype the session guard throws when the account changes
                // mid-call: the photos were not trashed, and the selection bar must hear that.
                mutableMutationEvents.trySend(GalleryMutationEvent.TrashFailed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableMutationEvents.trySend(GalleryMutationEvent.TrashFailed)
            } finally {
                mutationInFlight = false
            }
        }
    }

    /**
     * The user of the first initialised, settled account session, or null when none arrives
     * within [SESSION_WAIT_MILLIS] or the session settles without an account.
     */
    private suspend fun awaitSessionUserId(): UserId? =
        withTimeoutOrNull(SESSION_WAIT_MILLIS) {
            accountSession.first { state -> state.initialized && !state.transitioning }
        }?.activeUserId

    /**
     * The answer from the privacy dialog. Runs in this scope: the dialog is a fragment that
     * survives a rotation, and its write must too, or a rotation right after the toggle loses
     * it. The fragment manager also restores the dialog after a process death, where a Save may
     * land before the session has settled; like [trashPhotos] the write then waits for the
     * account (see [awaitSessionUserId]). None arriving, or the account having gone while the
     * dialog was up, is reported as not saved.
     */
    fun saveTelemetryPreference(enabled: Boolean) {
        viewModelScope.launch {
            val userId = currentUserId ?: awaitSessionUserId()
            val saved =
                userId != null &&
                    try {
                        telemetryWriter.setTelemetryEnabled(userId, enabled)
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        false
                    }
            mutableMutationEvents.trySend(GalleryMutationEvent.TelemetryPreferenceSaved(saved))
        }
    }

    fun requestRefresh(manual: Boolean = true) {
        val selectedDestination = destination
        val generation = if (manual) ++manualRefreshGeneration else manualRefreshGeneration
        if (manual) {
            setRefreshing(true)
            // The user asked for it: a pause set from the download notification is lifted.
            currentUserId?.let(protonThumbnailScheduler::clearPaused)
        }
        viewModelScope.launch {
            try {
                refresh(selectedDestination, forceRemote = manual)
            } finally {
                if (manual) {
                    if (generation == manualRefreshGeneration) setRefreshing(false)
                }
            }
        }
    }

    fun refreshAfterMutation() {
        requestRefresh(manual = false)
    }

    /**
     * Keeps the visible section current while the gallery is on screen. The caller scopes it
     * to the started lifecycle, so it pauses in the background and resumes with an immediate
     * check when the last one is overdue. A check asks for a quiet, non-forced refresh only when
     * the last completed refresh is old enough for the repository to enumerate again (see
     * [GalleryPeriodicSyncPolicy.shouldRefresh]); otherwise it does nothing.
     */
    suspend fun runPeriodicSync() {
        while (true) {
            delay(GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(lastPeriodicCheckMillis, clock.nowMillis()))
            val now = clock.nowMillis()
            lastPeriodicCheckMillis = now
            if (currentUserId != null &&
                !sessionTransitioning &&
                GalleryPeriodicSyncPolicy.shouldRefresh(lastRefreshCompletedMillis, now)
            ) {
                requestRefresh(manual = false)
            }
        }
    }

    fun resumeThumbnailDownloads() {
        val userId = currentUserId ?: return
        viewModelScope.launch(dispatchers.io) {
            protonThumbnailScheduler.resume(userId)
        }
    }

    fun disconnectProton() {
        val userId = currentUserId ?: return
        explicitDisconnect = true
        viewModelScope.launch {
            accountManager.removeAccount(userId)
            destination = GalleryNavigationPolicy.withoutAccount(destination)
            saveDestination()
            publishUiState()
        }
    }

    private fun observeAccountSession() {
        viewModelScope.launch {
            accountSession.collectLatest(::handleAccountSession)
        }
    }

    private fun observeAlbums() {
        viewModelScope.launch {
            protonRepository.albumsState.collect(::leaveMissingAlbum)
        }
    }

    /**
     * A restored album page whose album the loaded list no longer has (deleted on another
     * device) returns to the album list instead of showing "album is empty" under a stale name.
     */
    private fun leaveMissingAlbum(albums: ProtonAlbumsState) {
        val userId = currentUserId ?: return
        if (albums.userId != userId.id) return
        val fallback =
            GalleryNavigationPolicy.withoutAlbum(destination, albums.hasLoaded) { nodeUid ->
                albums.albums.any { album -> album.nodeUid == nodeUid }
            } ?: return
        selectDestination(fallback)
    }

    private suspend fun handleAccountSession(state: ProtonAccountSessionState) {
        val accountStatus = accountStatus(state)
        sessionTransitioning = state.transitioning
        if (!state.initialized) {
            publishUiState(accountStatus)
            return
        }
        val readyAccount = state.account?.takeIf(Account::isReady)
        val previousUserId = currentUserId
        val nextUserId = state.activeUserId
        val userChanged = previousUserId != nextUserId
        if (userChanged) currentUserId = nextUserId
        if (state.transitioning) {
            publishUiState(accountStatus)
            return
        }
        if (nextUserId != null) {
            // Back with an account: the notice has done its job, and a later removal is a new event.
            explicitDisconnect = false
            signedOut = false
        } else if (userChanged && !explicitDisconnect) {
            // The account was removed underneath the app (an expired session); say so rather than
            // fall back to the first-launch invitation as if the user had never connected.
            signedOut = true
        }
        if (readyAccount == null) {
            val fallback = GalleryNavigationPolicy.withoutAccount(destination)
            if (fallback != destination) {
                destination = fallback
                saveDestination()
            }
        }
        publishUiState(accountStatus)
        if (userChanged && nextUserId != null) {
            // The album list may already be loaded for this user; the collector saw it before the user was known.
            leaveMissingAlbum(protonRepository.albumsState.value)
            if (requestMissingProtonMetadata(nextUserId)) return
            protonThumbnailScheduler.restart(nextUserId)
        }
        requestRefresh(manual = false)
    }

    private fun requestMissingProtonMetadata(userId: UserId): Boolean {
        val timeline = protonRepository.state.value
        val albums = protonRepository.albumsState.value
        val loadTimeline = timeline.userId != userId.id || !timeline.hasLoaded
        val loadAlbums = albums.userId != userId.id || !albums.hasLoaded
        if (!loadTimeline && !loadAlbums) return false

        viewModelScope.launch {
            coroutineScope {
                if (loadTimeline) {
                    launch(dispatchers.io) {
                        protonRepository.syncTimelineMetadata(userId)
                    }
                }
                if (loadAlbums) {
                    launch(dispatchers.io) {
                        protonRepository.syncAlbumsMetadata(userId)
                    }
                }
            }
            // The page the user is on needs its own listing too, or a restore into it stays blank.
            val selected = destination
            if (selected is GalleryDestination.Tag) {
                withContext(dispatchers.io) {
                    protonRepository.syncTagMetadata(userId, selected.tag)
                }
            }
            if (selected is GalleryDestination.AlbumPhotos) {
                withContext(dispatchers.io) {
                    ensureCachedAlbumLoaded(userId, selected.album)
                    protonRepository.syncAlbumPhotoMetadata(userId, selected.album)
                }
            }
            if (currentUserId == userId) {
                protonThumbnailScheduler.restart(userId)
            }
        }
        return true
    }

    private suspend fun refresh(
        selectedDestination: GalleryDestination,
        forceRemote: Boolean,
    ) {
        val userId = currentUserId ?: return
        when (selectedDestination) {
            GalleryDestination.Timeline -> {
                withContext(dispatchers.io) {
                    refreshProtonSection(userId) {
                        protonRepository.syncTimelineMetadata(userId, forceRemote)
                    }
                }
            }

            is GalleryDestination.Tag -> {
                withContext(dispatchers.io) {
                    refreshProtonSection(userId) {
                        if (!protonRepository.state.value.hasLoaded) {
                            protonRepository.syncTimelineMetadata(userId, forceRemote)
                        }
                        protonRepository.syncTagMetadata(userId, selectedDestination.tag, forceRemote)
                    }
                }
            }

            GalleryDestination.Library -> {
                withContext(dispatchers.io) {
                    refreshProtonSection(userId) {
                        protonRepository.syncAlbumsMetadata(userId, forceRemote)
                    }
                }
            }

            is GalleryDestination.AlbumPhotos -> {
                withContext(dispatchers.io) {
                    ensureCachedAlbumLoaded(userId, selectedDestination.album)
                    protonRepository.syncAlbumPhotoMetadata(
                        userId,
                        selectedDestination.album,
                        forceRemote,
                    )
                }
                protonThumbnailScheduler.enqueue(userId)
            }
        }
        // The repositories swallow a failed sync and publish refreshFailed instead; stamping it as
        // completed would keep the periodic tick quiet for a full freshness limit while offline.
        if (!refreshFailed(selectedDestination)) lastRefreshCompletedMillis = clock.nowMillis()
    }

    /** Whether the section a refresh of [selectedDestination] syncs is now published as failed. */
    private fun refreshFailed(selectedDestination: GalleryDestination): Boolean =
        when (selectedDestination) {
            GalleryDestination.Timeline -> {
                protonRepository.state.value.refreshFailed
            }

            is GalleryDestination.Tag -> {
                protonRepository.state.value.tags[selectedDestination.tag]
                    ?.refreshFailed ?: false
            }

            GalleryDestination.Library -> {
                protonRepository.albumsState.value.refreshFailed
            }

            is GalleryDestination.AlbumPhotos -> {
                protonRepository.albumPhotosState.value.refreshFailed
            }
        }

    /**
     * Puts the album's cached photos on screen before its sync runs. [openAlbum] does this on
     * the way in; a restored album destination (a recreation, or a cold start into it) has no
     * such step and would show an empty page until the sync settled.
     */
    private suspend fun ensureCachedAlbumLoaded(
        userId: UserId,
        album: ProtonAlbumReference,
    ) {
        val current = protonRepository.albumPhotosState.value
        if (current.userId == userId.id && current.albumUid == album.nodeUid && current.hasLoaded) return
        protonRepository.loadCachedAlbum(userId, album)
    }

    private suspend fun refreshProtonSection(
        userId: UserId,
        sync: suspend () -> Unit,
    ) {
        sync()
        protonThumbnailScheduler.enqueue(userId)
    }

    /** Pushes the current destination and user (and, when given, account status) into the state flow. */
    private fun publishUiState(accountStatus: ProtonAccountStatus? = null) {
        localInputs.update { inputs ->
            inputs.copy(
                destination = destination,
                currentUserId = currentUserId,
                accountStatus = accountStatus ?: inputs.accountStatus,
                signedOut = signedOut,
            )
        }
    }

    private fun setRefreshing(refreshing: Boolean) {
        localInputs.update { inputs -> inputs.copy(isRefreshing = refreshing) }
    }

    private data class LocalInputs(
        val destination: GalleryDestination,
        val currentUserId: UserId?,
        val accountStatus: ProtonAccountStatus,
        val isRefreshing: Boolean,
        val signedOut: Boolean = false,
    )

    private fun saveDestination() {
        val stored = GalleryNavigationCodec.encode(destination)
        savedStateHandle[STATE_DESTINATION] = stored.destination
        savedStateHandle[STATE_ALBUM_UID] = stored.albumUid
        savedStateHandle[STATE_ALBUM_NAME] = stored.albumName
        savedStateHandle[STATE_TAG] = stored.tag
        persistScrollPosition()
        setSelection(emptySet())
        // A cold start reopens the tab root, never a deep collection or album.
        navigationStore.write(GalleryNavigationPolicy.root(destination))
    }

    /** The saved state carries the position of the current page only; it follows the destination. */
    private fun persistScrollPosition() {
        val position = scrollPositions.positionFor(destination)
        if (position == null) {
            savedStateHandle.remove<Int>(STATE_SCROLL_FIRST_VISIBLE)
            savedStateHandle.remove<Int>(STATE_SCROLL_TOP_OFFSET)
        } else {
            savedStateHandle[STATE_SCROLL_FIRST_VISIBLE] = position.firstVisiblePosition
            savedStateHandle[STATE_SCROLL_TOP_OFFSET] = position.topOffset
        }
    }

    private companion object {
        const val STATE_DESTINATION = "gallery.destination"
        const val STATE_ALBUM_UID = "gallery.album-uid"
        const val STATE_ALBUM_NAME = "gallery.album-name"
        const val STATE_TAG = "gallery.proton-tag"
        const val STATE_SCROLL_FIRST_VISIBLE = "gallery.scroll-first-visible"
        const val STATE_SCROLL_TOP_OFFSET = "gallery.scroll-top-offset"
        const val STATE_SELECTION = "gallery.selection"
        const val MUTATION_EVENT_BUFFER = 16
        const val UI_STATE_STOP_TIMEOUT_MILLIS = 5_000L
        const val SESSION_WAIT_MILLIS = 10_000L

        private fun accountStatus(state: ProtonAccountSessionState): ProtonAccountStatus =
            ProtonAccountStatus.resolve(
                initialized = state.initialized,
                transitioning = state.transitioning,
                hasAccount = state.account != null,
                accountIsReady = state.account?.isReady() == true,
            )

        private fun uiInputs(
            gallery: ProtonGalleryState,
            albums: ProtonAlbumsState,
            albumPhotos: ProtonAlbumPhotosState,
            local: LocalInputs,
        ) = GalleryUiInputs(
            destination = local.destination,
            protonGallery = gallery,
            protonAlbums = albums,
            protonAlbumPhotos = albumPhotos,
            currentUserId = local.currentUserId,
            protonAccountStatus = local.accountStatus,
            isRefreshing = local.isRefreshing,
            signedOut = local.signedOut,
        )

        fun restoreNavigation(state: SavedStateHandle): GalleryDestination? =
            GalleryNavigationCodec.decode(
                StoredGalleryNavigation(
                    destination = state[STATE_DESTINATION],
                    albumUid = state[STATE_ALBUM_UID],
                    albumName = state[STATE_ALBUM_NAME],
                    tag = state[STATE_TAG],
                ),
            )

        fun restoreScrollPosition(state: SavedStateHandle): GalleryScrollPosition? {
            val firstVisible = state.get<Int>(STATE_SCROLL_FIRST_VISIBLE) ?: return null
            val topOffset = state.get<Int>(STATE_SCROLL_TOP_OFFSET) ?: return null
            return GalleryScrollPosition(firstVisiblePosition = firstVisible, topOffset = topOffset)
        }
    }
}
