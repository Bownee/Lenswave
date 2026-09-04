package com.bownee.lenswave.gallery

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonThumbnailScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.isReady
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        private val accountManager: AccountManager,
        private val protonRepository: ProtonGalleryReader,
        private val protonThumbnailScheduler: ProtonThumbnailScheduler,
        private val accountSessionManager: ProtonAccountSessionManager,
        private val navigationStore: GalleryNavigationStore,
        private val savedStateHandle: SavedStateHandle,
        private val clock: LenswaveClock,
    ) : ViewModel() {
        private val uiStateFactory = GalleryUiStateFactory(AndroidGalleryText(context.resources))

        private var destination =
            restoreNavigation(savedStateHandle)
                ?: navigationStore.read()
                ?: GalleryDestination.Timeline
        private var currentUserId: UserId? = null
        private var sessionTransitioning = false
        private var manualRefreshGeneration = 0
        private var lastPeriodicCheckMillis: Long? = null

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
                .flowOn(Dispatchers.Default)
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
            observeAccountSession()
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
                        withContext(Dispatchers.IO) { protonRepository.loadCachedAlbum(userId, albumReference) }
                    }
                } finally {
                    // Whatever the cache said, the navigation must land; a later change wins.
                    publishUiState()
                }
                if (userId != null) protonThumbnailScheduler.enqueue(userId)
                requestRefresh(manual = false)
            }
        }

        fun requestRefresh(manual: Boolean = true) {
            val selectedDestination = destination
            val generation = if (manual) ++manualRefreshGeneration else manualRefreshGeneration
            if (manual) setRefreshing(true)
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
         * check when the last one is overdue. Each check is a quiet, non-forced refresh: the
         * repository re-enumerates only once the cached listing is older than its freshness limit.
         */
        suspend fun runPeriodicSync() {
            while (true) {
                delay(GalleryPeriodicSyncPolicy.delayUntilNextCheckMillis(lastPeriodicCheckMillis, clock.nowMillis()))
                lastPeriodicCheckMillis = clock.nowMillis()
                if (currentUserId != null && !sessionTransitioning) requestRefresh(manual = false)
            }
        }

        fun resumeThumbnailDownloads() {
            val userId = currentUserId ?: return
            viewModelScope.launch(Dispatchers.IO) {
                protonThumbnailScheduler.resume(userId)
            }
        }

        fun disconnectProton() {
            val userId = currentUserId ?: return
            viewModelScope.launch {
                accountManager.removeAccount(userId)
                destination = GalleryNavigationPolicy.withoutAccount(destination)
                saveDestination()
                publishUiState()
            }
        }

        private fun observeAccountSession() {
            viewModelScope.launch {
                accountSessionManager.state.collectLatest(::handleAccountSession)
            }
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
            if (readyAccount == null) {
                val fallback = GalleryNavigationPolicy.withoutAccount(destination)
                if (fallback != destination) {
                    destination = fallback
                    saveDestination()
                }
            }
            publishUiState(accountStatus)
            if (userChanged && nextUserId != null) {
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
                        launch(Dispatchers.IO) {
                            protonRepository.syncTimelineMetadata(userId)
                        }
                    }
                    if (loadAlbums) {
                        launch(Dispatchers.IO) {
                            protonRepository.syncAlbumsMetadata(userId)
                        }
                    }
                }
                (destination as? GalleryDestination.Tag)?.let { selected ->
                    withContext(Dispatchers.IO) {
                        protonRepository.syncTagMetadata(userId, selected.tag)
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
                    withContext(Dispatchers.IO) {
                        refreshProtonSection(userId) {
                            protonRepository.syncTimelineMetadata(userId, forceRemote)
                        }
                    }
                }

                is GalleryDestination.Tag -> {
                    withContext(Dispatchers.IO) {
                        refreshProtonSection(userId) {
                            if (!protonRepository.state.value.hasLoaded) {
                                protonRepository.syncTimelineMetadata(userId, forceRemote)
                            }
                            protonRepository.syncTagMetadata(userId, selectedDestination.tag, forceRemote)
                        }
                    }
                }

                GalleryDestination.Library -> {
                    withContext(Dispatchers.IO) {
                        refreshProtonSection(userId) {
                            protonRepository.syncAlbumsMetadata(userId, forceRemote)
                        }
                    }
                }

                is GalleryDestination.AlbumPhotos -> {
                    withContext(Dispatchers.IO) {
                        protonRepository.syncAlbumPhotoMetadata(
                            userId,
                            selectedDestination.album,
                            forceRemote,
                        )
                    }
                    protonThumbnailScheduler.enqueue(userId)
                }
            }
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
        )

        private fun saveDestination() {
            val stored = GalleryNavigationCodec.encode(destination)
            savedStateHandle[STATE_DESTINATION] = stored.destination
            savedStateHandle[STATE_ALBUM_UID] = stored.albumUid
            savedStateHandle[STATE_ALBUM_NAME] = stored.albumName
            savedStateHandle[STATE_TAG] = stored.tag
            // A cold start reopens the tab root, never a deep collection or album.
            navigationStore.write(GalleryNavigationPolicy.root(destination))
        }

        private companion object {
            const val STATE_DESTINATION = "gallery.destination"
            const val STATE_ALBUM_UID = "gallery.album-uid"
            const val STATE_ALBUM_NAME = "gallery.album-name"
            const val STATE_TAG = "gallery.proton-tag"
            const val UI_STATE_STOP_TIMEOUT_MILLIS = 5_000L

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
        }
    }
