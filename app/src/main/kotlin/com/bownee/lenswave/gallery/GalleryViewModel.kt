package com.bownee.lenswave.gallery

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.proton.ProtonAlbumPhotosState
import com.bownee.lenswave.proton.ProtonAlbumsState
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMetadataState
import com.bownee.lenswave.proton.ProtonThumbnailScheduler
import com.bownee.lenswave.proton.ProtonTrashState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.isReady
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId

@HiltViewModel
class GalleryViewModel @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val deviceRepository: DevicePhotoSource,
    private val protonRepository: ProtonGalleryReader,
    private val protonThumbnailScheduler: ProtonThumbnailScheduler,
    private val combinedRepository: CombinedPhotoMatcher,
    private val accountSessionManager: ProtonAccountSessionManager,
    private val navigationStore: GalleryNavigationStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val uiStateFactory = GalleryUiStateFactory(AndroidGalleryText(context.resources))
    private val mutableUiState = MutableStateFlow(GalleryUiState())
    private val deviceLoadMutex = Mutex()
    private val deviceTrashLoadMutex = Mutex()

    private val restoredNavigation = restoreNavigation(savedStateHandle) ?: navigationStore.read()
    private var destination = restoredNavigation?.destination ?: GalleryDestination.Device()
    private var account: Account? = null
    private var currentUserId: UserId? = null
    private var sessionTransitioning = false
    private var hasChosenStartupDestination = restoredNavigation != null
    private var deviceAccessLevel = DeviceAccessLevel.NONE
    private val hasDeviceAccess get() = deviceAccessLevel != DeviceAccessLevel.NONE
    private var selectedDeviceCollection = restoredNavigation?.selectedDeviceCollection
        ?: DeviceCollection.CAMERA
    private var devicePhotos = GallerySourceSnapshot<GalleryAsset>()
    private var deviceTrash = GallerySourceSnapshot<GalleryAsset>()
    private var protonGalleryState = ProtonGalleryState()
    private var protonMetadataState = ProtonMetadataState()
    private var protonAlbumsState = ProtonAlbumsState()
    private var protonAlbumPhotosState = ProtonAlbumPhotosState()
    private var protonTrashState = ProtonTrashState()
    private var combinedMatches: Map<String, List<String>> = emptyMap()
    private var combinedMatchProgress = CombinedMatchProgress(complete = true)
    private var combinedMatchInputKey: String? = null
    private var combinedMatchJob: Job? = null
    private var albumThumbnailJob: Job? = null
    private var combinedMatchGeneration = 0L
    private var deviceAccessGeneration = 0L
    private var manualRefreshGeneration = 0
    private var metadataCheckedUserId: String? = null

    val uiState: StateFlow<GalleryUiState> = mutableUiState.asStateFlow()

    init {
        observeAccountSession()
        observeProtonSources()
    }

    fun setDeviceAccess(level: DeviceAccessLevel) {
        val accessChanged = GalleryOperationPolicy.shouldInvalidateDeviceSnapshots(deviceAccessLevel, level)
        deviceAccessLevel = level
        if (accessChanged) {
            deviceAccessGeneration++
            devicePhotos = GallerySourceSnapshot()
            deviceTrash = GallerySourceSnapshot()
            resetCombinedMatching()
        }
        publishUiState()
        if (level != DeviceAccessLevel.NONE) requestRefresh(manual = false)
    }

    fun selectDestination(newDestination: GalleryDestination) {
        if (destination == newDestination) {
            publishUiState()
            return
        }
        val previousDestination = destination
        destination = newDestination
        if (previousDestination is GalleryDestination.ProtonAlbumPhotos &&
            previousDestination != newDestination
        ) {
            albumThumbnailJob?.cancel()
            albumThumbnailJob = null
        }
        if (previousDestination == GalleryDestination.Combined && newDestination != GalleryDestination.Combined) {
            resetCombinedMatching()
        }
        if (newDestination is GalleryDestination.Device) {
            selectedDeviceCollection = newDestination.collection
        }
        saveDestination()
        publishUiState()
        if (previousDestination is GalleryDestination.Device &&
            newDestination is GalleryDestination.Device &&
            devicePhotos.hasLoaded
        ) return
        requestRefresh(manual = false)
    }

    fun openAlbum(album: com.bownee.lenswave.proton.ProtonAlbum) {
        if (destination == GalleryDestination.Combined) resetCombinedMatching()
        albumThumbnailJob?.cancel()
        albumThumbnailJob = null
        val albumReference = album.reference()
        destination = GalleryDestination.ProtonAlbumPhotos(albumReference)
        saveDestination()
        publishUiState()
        viewModelScope.launch {
            currentUserId?.let { userId ->
                withContext(Dispatchers.IO) { protonRepository.loadCachedAlbum(userId, albumReference) }
            }
            publishUiState()
            requestRefresh(manual = false)
        }
    }

    fun closeAlbum() {
        if (destination !is GalleryDestination.ProtonAlbumPhotos) return
        selectDestination(GalleryDestination.ProtonAlbums)
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
        metadataCheckedUserId = null
        requestRefresh(manual = false)
    }

    fun disconnectProton() {
        val userId = currentUserId ?: return
        viewModelScope.launch {
            resetCombinedMatchingAndJoin()
            accountManager.removeAccount(userId)
            destination = GalleryDestination.Device()
            selectedDeviceCollection = DeviceCollection.CAMERA
            saveDestination()
            publishUiState()
            requestRefresh(manual = false)
        }
    }

    private fun observeAccountSession() {
        viewModelScope.launch {
            accountSessionManager.state.collectLatest(::handleAccountSession)
        }
    }

    private fun observeProtonSources() {
        viewModelScope.launch {
            protonRepository.metadataState.collectLatest { state ->
                protonMetadataState = state
                publishUiState()
            }
        }
        viewModelScope.launch {
            protonRepository.state.collectLatest { state ->
                protonGalleryState = state
                if (state.syncing) pauseCombinedMatching() else startCombinedMatchingIfNeeded()
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
        sessionTransitioning = state.transitioning
        if (!state.initialized) {
            publishUiState()
            return
        }
        val readyAccount = state.account?.takeIf(Account::isReady)
        val previousUserId = currentUserId
        val nextUserId = state.activeUserId
        if (previousUserId != nextUserId) {
            resetCombinedMatchingAndJoin()
            albumThumbnailJob?.cancelAndJoin()
            albumThumbnailJob = null
            currentUserId = nextUserId
            metadataCheckedUserId = null
        }
        if (state.transitioning) {
            publishUiState()
            return
        }
        if (!hasChosenStartupDestination) {
            hasChosenStartupDestination = true
            destination = if (readyAccount == null) {
                GalleryDestination.Device()
            } else {
                GalleryDestination.ProtonTimeline
            }
            saveDestination()
        } else if (readyAccount == null && destination.space != GallerySpace.DEVICE) {
            destination = GalleryDestination.Device(selectedDeviceCollection)
            saveDestination()
        }
        publishUiState()
        requestRefresh(manual = false)
    }

    private suspend fun refresh(selectedDestination: GalleryDestination, forceRemote: Boolean) {
        when (selectedDestination) {
            GalleryDestination.Combined -> coroutineScope {
                val deviceRefresh = if (hasDeviceAccess) async { loadDevicePhotos() } else null
                val protonRefresh = currentUserId?.let { userId ->
                    async(Dispatchers.IO) { refreshProtonMetadata(userId, forceRemote) }
                }
                deviceRefresh?.await()
                protonRefresh?.await()
                startCombinedMatchingIfNeeded(forceRecheck = forceRemote)?.join()
            }
            is GalleryDestination.Device -> if (hasDeviceAccess) loadDevicePhotos()
            GalleryDestination.ProtonTimeline -> currentUserId?.let { userId ->
                withContext(Dispatchers.IO) {
                    refreshProtonMetadata(userId, forceRemote)
                }
            }
            GalleryDestination.ProtonAlbums -> currentUserId?.let { userId ->
                withContext(Dispatchers.IO) {
                    refreshProtonMetadata(userId, forceRemote)
                }
            }
            is GalleryDestination.ProtonAlbumPhotos -> currentUserId?.let { userId ->
                albumThumbnailJob?.cancelAndJoin()
                albumThumbnailJob = null
                protonThumbnailScheduler.cancelAndAwait(userId)
                withContext(Dispatchers.IO) {
                    protonRepository.syncAlbumPhotoMetadata(
                        userId,
                        selectedDestination.album,
                        forceRemote,
                    )
                }
                val albumState = protonRepository.albumPhotosState.value
                if (albumState.albumUid == selectedDestination.album.nodeUid && albumState.hasLoaded) {
                    startAlbumThumbnailHydration(userId, selectedDestination)
                } else if (protonMetadataState.hasLoaded) {
                    protonThumbnailScheduler.enqueue(userId)
                }
            }
            is GalleryDestination.Trash -> when (selectedDestination.source) {
                PhotoSource.DEVICE -> if (hasDeviceAccess) loadDeviceTrash()
                PhotoSource.PROTON -> currentUserId?.let { userId ->
                    withContext(Dispatchers.IO) {
                        refreshProtonMetadata(userId, forceRemote)
                    }
                }
            }
        }
    }

    private suspend fun refreshProtonMetadata(userId: UserId, forceRemote: Boolean) {
        if (!forceRemote && metadataCheckedUserId == userId.id && protonMetadataState.hasLoaded) return
        protonThumbnailScheduler.cancelAndAwait(userId)
        protonRepository.syncMetadata(userId, forceRemote)
        val metadata = protonRepository.metadataState.value
        if (metadata.userId == userId.id && metadata.hasLoaded) {
            metadataCheckedUserId = userId.id
            protonThumbnailScheduler.enqueue(userId)
        }
    }

    private fun startAlbumThumbnailHydration(
        userId: UserId,
        destinationAtStart: GalleryDestination.ProtonAlbumPhotos,
    ) {
        if (currentUserId != userId || destination != destinationAtStart) return
        albumThumbnailJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                protonRepository.hydrateAlbumPhotoThumbnails(userId, destinationAtStart.album)
            } finally {
                if (currentUserId == userId && protonRepository.metadataState.value.hasLoaded) {
                    protonThumbnailScheduler.enqueue(userId)
                }
            }
        }
    }

    private suspend fun loadDevicePhotos() {
        val accessGeneration = deviceAccessGeneration
        deviceLoadMutex.withLock {
            if (!isCurrentDeviceLoad(accessGeneration)) return@withLock
            devicePhotos = devicePhotos.copy(isLoading = true, errorMessage = null)
            publishUiState()
            try {
                val loadedPhotos = deviceRepository.loadPhotos()
                if (!isCurrentDeviceLoad(accessGeneration)) return@withLock
                devicePhotos = GallerySourceSnapshot(
                    items = loadedPhotos,
                    hasLoaded = true,
                )
                startCombinedMatchingIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure("device-photo-load", error)
                if (!isCurrentDeviceLoad(accessGeneration)) return@withLock
                devicePhotos = devicePhotos.copy(
                    isLoading = false,
                    errorMessage = context.getString(com.bownee.lenswave.R.string.could_not_load_device_photos),
                )
            } finally {
                if (isCurrentDeviceLoad(accessGeneration)) {
                    devicePhotos = devicePhotos.copy(isLoading = false)
                    publishUiState()
                }
            }
        }
    }

    private suspend fun loadDeviceTrash() {
        val accessGeneration = deviceAccessGeneration
        deviceTrashLoadMutex.withLock {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                !isCurrentDeviceLoad(accessGeneration)
            ) return@withLock
            deviceTrash = deviceTrash.copy(isLoading = true, errorMessage = null)
            publishUiState()
            try {
                val loadedPhotos = deviceRepository.loadTrashedPhotos()
                if (!isCurrentDeviceLoad(accessGeneration)) return@withLock
                deviceTrash = GallerySourceSnapshot(
                    items = loadedPhotos,
                    hasLoaded = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure("device-trash-load", error)
                if (!isCurrentDeviceLoad(accessGeneration)) return@withLock
                deviceTrash = deviceTrash.copy(
                    isLoading = false,
                    errorMessage = context.getString(com.bownee.lenswave.R.string.could_not_load_device_trash),
                )
            } finally {
                if (isCurrentDeviceLoad(accessGeneration)) {
                    deviceTrash = deviceTrash.copy(isLoading = false)
                    publishUiState()
                }
            }
        }
    }

    private fun startCombinedMatchingIfNeeded(forceRecheck: Boolean = false): Job? {
        if (!GalleryOperationPolicy.canStartCombinedMatching(
                destination = destination,
                accessLevel = deviceAccessLevel,
                sessionTransitioning = sessionTransitioning,
                userId = currentUserId,
                devicePhotosLoaded = devicePhotos.hasLoaded,
                protonSyncing = protonGalleryState.syncing,
            )
        ) return null
        val userId = requireNotNull(currentUserId)
        val devicePhotoSnapshot = devicePhotos.items
        val protonPhotoSnapshot = protonGalleryState.photos
        val inputKey = CombinedGallery.timelineFingerprint(buildList {
            add("user:${userId.id}")
            protonPhotoSnapshot.forEach { add("proton:${it.nodeUid}") }
            devicePhotoSnapshot.forEach { asset ->
                add("device:${asset.stableId}:${asset.displayName}:${asset.sizeBytes}:${asset.modifiedAtEpochMillis}")
            }
        })
        if (!forceRecheck && combinedMatchInputKey == inputKey &&
            (combinedMatchJob?.isActive == true || !CombinedGallery.shouldRetry(combinedMatchProgress))
        ) return combinedMatchJob

        combinedMatchInputKey = inputKey
        combinedMatchJob?.cancel()
        val matchGeneration = ++combinedMatchGeneration
        combinedMatches = emptyMap()
        if (protonPhotoSnapshot.isEmpty() || devicePhotoSnapshot.isEmpty()) {
            combinedMatchProgress = CombinedMatchProgress(complete = true)
            publishUiState()
            return null
        }

        combinedMatchProgress = CombinedMatchProgress(checkCount = devicePhotoSnapshot.size)
        return viewModelScope.launch {
            try {
                combinedRepository.resolveMatches(
                    userId = userId,
                    devicePhotos = devicePhotoSnapshot,
                    protonPhotos = protonPhotoSnapshot,
                    forceRecheck = forceRecheck,
                ) { progress ->
                    if (!isCurrentCombinedMatch(matchGeneration, inputKey, userId)) return@resolveMatches
                    combinedMatches = progress.matches
                    combinedMatchProgress = progress
                    publishUiState()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure("combined-match-session", error)
                if (!isCurrentCombinedMatch(matchGeneration, inputKey, userId)) return@launch
                combinedMatchProgress = CombinedMatchProgress(
                    matches = combinedMatches,
                    complete = true,
                    errorMessage = context.getString(com.bownee.lenswave.R.string.duplicate_check_failed),
                )
                publishUiState()
            }
        }.also { combinedMatchJob = it }
    }

    private fun pauseCombinedMatching() {
        combinedMatchGeneration++
        combinedMatchJob?.cancel()
        combinedMatchJob = null
        combinedMatchInputKey = null
    }

    private fun resetCombinedMatching() {
        pauseCombinedMatching()
        combinedMatches = emptyMap()
        combinedMatchProgress = CombinedMatchProgress(complete = true)
    }

    private suspend fun resetCombinedMatchingAndJoin() {
        val activeJob = combinedMatchJob
        resetCombinedMatching()
        activeJob?.cancelAndJoin()
    }

    private fun isCurrentCombinedMatch(generation: Long, inputKey: String, userId: UserId): Boolean =
        generation == combinedMatchGeneration &&
            combinedMatchInputKey == inputKey &&
            currentUserId == userId &&
            destination == GalleryDestination.Combined &&
            hasDeviceAccess &&
            !sessionTransitioning

    private fun isCurrentDeviceLoad(generation: Long): Boolean =
        GalleryOperationPolicy.isCurrentDeviceLoad(generation, deviceAccessGeneration, deviceAccessLevel)

    private fun publishUiState(isRefreshing: Boolean = mutableUiState.value.isRefreshing) {
        mutableUiState.value = uiStateFactory.create(
            GalleryUiInputs(
                destination = destination,
                hasDeviceAccess = hasDeviceAccess,
                supportsDeviceTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                selectedDeviceCollection = selectedDeviceCollection,
                devicePhotos = devicePhotos,
                deviceTrash = deviceTrash,
                protonGallery = protonGalleryState,
                protonMetadata = protonMetadataState,
                protonAlbums = protonAlbumsState,
                protonAlbumPhotos = protonAlbumPhotosState,
                protonTrash = protonTrashState,
                combinedMatches = combinedMatches,
                combinedMatchProgress = combinedMatchProgress,
                currentUserId = currentUserId,
                protonAccountStatus = when {
                    sessionTransitioning -> ProtonAccountStatus.CONNECTING
                    account == null -> ProtonAccountStatus.DISCONNECTED
                    account?.isReady() == true -> ProtonAccountStatus.CONNECTED
                    else -> ProtonAccountStatus.CONNECTING
                },
                isRefreshing = isRefreshing,
            ),
        )
    }

    private fun saveDestination() {
        val navigation = GalleryNavigationState(destination, selectedDeviceCollection)
        val stored = GalleryNavigationCodec.encode(navigation)
        savedStateHandle[STATE_DESTINATION] = stored.destination
        savedStateHandle[STATE_DEVICE_COLLECTION] = stored.deviceCollection
        savedStateHandle[STATE_TRASH_SOURCE] = stored.trashSource
        savedStateHandle[STATE_ALBUM_UID] = stored.albumUid
        savedStateHandle[STATE_ALBUM_NAME] = stored.albumName
        navigationStore.write(navigation)
    }

    private companion object {
        const val STATE_DESTINATION = "gallery.destination"
        const val STATE_DEVICE_COLLECTION = "gallery.device-collection"
        const val STATE_TRASH_SOURCE = "gallery.trash-source"
        const val STATE_ALBUM_UID = "gallery.album-uid"
        const val STATE_ALBUM_NAME = "gallery.album-name"

        fun restoreNavigation(state: SavedStateHandle): GalleryNavigationState? =
            GalleryNavigationCodec.decode(
                StoredGalleryNavigation(
                    destination = state[STATE_DESTINATION],
                    deviceCollection = state[STATE_DEVICE_COLLECTION],
                    trashSource = state[STATE_TRASH_SOURCE],
                    albumUid = state[STATE_ALBUM_UID],
                    albumName = state[STATE_ALBUM_NAME],
                ),
            )
    }
}
