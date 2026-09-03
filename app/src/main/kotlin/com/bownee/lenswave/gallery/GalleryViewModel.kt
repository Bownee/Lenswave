package com.bownee.lenswave.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonThumbnailScheduler
import com.bownee.lenswave.proton.ProtonTrashState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.isReady
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId

@HiltViewModel
class GalleryViewModel @Inject internal constructor(
    @ApplicationContext context: Context,
    private val accountManager: AccountManager,
    private val protonRepository: ProtonGalleryReader,
    private val protonThumbnailScheduler: ProtonThumbnailScheduler,
    private val accountSessionManager: ProtonAccountSessionManager,
    private val navigationStore: GalleryNavigationStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val uiStateFactory = GalleryUiStateFactory(AndroidGalleryText(context.resources))
    private val mutableUiState = MutableStateFlow(GalleryUiState())

    private var destination = restoreNavigation(savedStateHandle)
        ?: navigationStore.read()
        ?: GalleryDestination.Timeline
    private var account: Account? = null
    private var currentUserId: UserId? = null
    private var accountSessionInitialized = false
    private var sessionTransitioning = false
    private var protonGalleryState = ProtonGalleryState()
    private var protonAlbumsState = ProtonAlbumsState()
    private var protonAlbumPhotosState = ProtonAlbumPhotosState()
    private var protonTrashState = ProtonTrashState()
    private var manualRefreshGeneration = 0

    val uiState: StateFlow<GalleryUiState> = mutableUiState.asStateFlow()

    init {
        observeAccountSession()
        observeProtonSources()
    }

    fun selectDestination(newDestination: GalleryDestination) {
        if (destination == newDestination) {
            publishUiState()
            return
        }
        destination = newDestination
        saveDestination()
        publishUiState()
        requestRefresh(manual = false)
    }

    fun openPhotosTab() {
        selectDestination(GalleryDestination.Timeline)
    }

    fun openLibrary() {
        selectDestination(GalleryDestination.Library)
    }

    fun navigateUp() {
        GalleryNavigationPolicy.parent(destination)?.let(::selectDestination)
    }

    fun openAlbum(album: ProtonAlbum) {
        val albumReference = album.reference()
        destination = GalleryDestination.AlbumPhotos(albumReference)
        saveDestination()
        publishUiState()
        viewModelScope.launch {
            currentUserId?.let { userId ->
                withContext(Dispatchers.IO) { protonRepository.loadCachedAlbum(userId, albumReference) }
                protonThumbnailScheduler.enqueue(userId)
            }
            publishUiState()
            requestRefresh(manual = false)
        }
    }

    fun requestRefresh(manual: Boolean = true) {
        val selectedDestination = destination
        val generation = if (manual) ++manualRefreshGeneration else manualRefreshGeneration
        if (manual) publishUiState(isRefreshing = true)
        viewModelScope.launch {
            try {
                refresh(selectedDestination, forceRemote = manual)
            } finally {
                if (manual) {
                    if (generation == manualRefreshGeneration) publishUiState(isRefreshing = false)
                }
            }
        }
    }

    fun refreshAfterMutation() {
        requestRefresh(manual = false)
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

    private fun observeProtonSources() {
        viewModelScope.launch {
            protonRepository.state.collectLatest { state ->
                protonGalleryState = state
                publishUiState()
            }
        }
        viewModelScope.launch {
            protonRepository.albumsState.collectLatest { state ->
                protonAlbumsState = state
                publishUiState()
            }
        }
        viewModelScope.launch {
            protonRepository.albumPhotosState.collectLatest { state ->
                protonAlbumPhotosState = state
                publishUiState()
            }
        }
        viewModelScope.launch {
            protonRepository.trashState.collectLatest { state ->
                protonTrashState = state
                publishUiState()
            }
        }
    }

    private suspend fun handleAccountSession(state: ProtonAccountSessionState) {
        account = state.account
        accountSessionInitialized = state.initialized
        sessionTransitioning = state.transitioning
        if (!state.initialized) {
            publishUiState()
            return
        }
        val readyAccount = state.account?.takeIf(Account::isReady)
        val previousUserId = currentUserId
        val nextUserId = state.activeUserId
        val userChanged = previousUserId != nextUserId
        if (userChanged) currentUserId = nextUserId
        if (state.transitioning) {
            publishUiState()
            return
        }
        if (readyAccount == null) {
            val fallback = GalleryNavigationPolicy.withoutAccount(destination)
            if (fallback != destination) {
                destination = fallback
                saveDestination()
            }
        }
        publishUiState()
        if (userChanged && nextUserId != null) {
            if (requestMissingProtonMetadata(nextUserId)) return
            protonThumbnailScheduler.restart(nextUserId)
        }
        requestRefresh(manual = false)
    }

    private fun requestMissingProtonMetadata(userId: UserId): Boolean {
        val timeline = protonRepository.state.value
        val albums = protonRepository.albumsState.value
        val trash = protonRepository.trashState.value
        val loadTimeline = timeline.userId != userId.id || !timeline.hasLoaded
        val loadAlbums = albums.userId != userId.id || !albums.hasLoaded
        val loadTrash = trash.userId != userId.id || !trash.hasLoaded
        if (!loadTimeline && !loadAlbums && !loadTrash) return false

        viewModelScope.launch {
            coroutineScope {
                if (loadTimeline) launch(Dispatchers.IO) {
                    protonRepository.syncTimelineMetadata(userId)
                }
                if (loadAlbums) launch(Dispatchers.IO) {
                    protonRepository.syncAlbumsMetadata(userId)
                }
                if (loadTrash) launch(Dispatchers.IO) {
                    protonRepository.syncTrashMetadata(userId)
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

    private suspend fun refresh(selectedDestination: GalleryDestination, forceRemote: Boolean) {
        val userId = currentUserId ?: return
        when (selectedDestination) {
            GalleryDestination.Timeline -> withContext(Dispatchers.IO) {
                refreshProtonSection(userId) {
                    protonRepository.syncTimelineMetadata(userId, forceRemote)
                }
            }
            is GalleryDestination.Tag -> withContext(Dispatchers.IO) {
                refreshProtonSection(userId) {
                    if (!protonRepository.state.value.hasLoaded) {
                        protonRepository.syncTimelineMetadata(userId, forceRemote)
                    }
                    protonRepository.syncTagMetadata(userId, selectedDestination.tag, forceRemote)
                }
            }
            GalleryDestination.Library -> withContext(Dispatchers.IO) {
                refreshProtonSection(userId) {
                    protonRepository.syncAlbumsMetadata(userId, forceRemote)
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
            GalleryDestination.Trash -> withContext(Dispatchers.IO) {
                refreshProtonSection(userId) {
                    protonRepository.syncTrashMetadata(userId, forceRemote)
                }
            }
        }
    }

    private suspend fun refreshProtonSection(userId: UserId, sync: suspend () -> Unit) {
        sync()
        protonThumbnailScheduler.enqueue(userId)
    }

    private fun publishUiState(isRefreshing: Boolean = mutableUiState.value.isRefreshing) {
        mutableUiState.value = uiStateFactory.create(
            GalleryUiInputs(
                destination = destination,
                protonGallery = protonGalleryState,
                protonAlbums = protonAlbumsState,
                protonAlbumPhotos = protonAlbumPhotosState,
                protonTrash = protonTrashState,
                currentUserId = currentUserId,
                protonAccountStatus = ProtonAccountStatus.resolve(
                    initialized = accountSessionInitialized,
                    transitioning = sessionTransitioning,
                    hasAccount = account != null,
                    accountIsReady = account?.isReady() == true,
                ),
                isRefreshing = isRefreshing,
            ),
        )
    }

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
