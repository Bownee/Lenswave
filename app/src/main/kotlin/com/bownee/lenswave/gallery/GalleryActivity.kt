package com.bownee.lenswave.gallery

import android.app.Activity
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bownee.lenswave.LenswaveApplication
import com.bownee.lenswave.R
import com.bownee.lenswave.applyBottomOverlayInsets
import com.bownee.lenswave.configureEdgeToEdgeWindow
import com.bownee.lenswave.dp
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryAuthCoordinator
import com.bownee.lenswave.gallery.GalleryContent
import com.bownee.lenswave.gallery.GalleryDeletionCoordinator
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.GalleryEmptyAction
import com.bownee.lenswave.gallery.GalleryFastScrollLayoutPolicy
import com.bownee.lenswave.gallery.GalleryNavigationPolicy
import com.bownee.lenswave.gallery.GalleryNotificationPermissionPrompter
import com.bownee.lenswave.gallery.GalleryScrollPosition
import com.bownee.lenswave.gallery.GalleryScrollPositionStore
import com.bownee.lenswave.gallery.GallerySettingsPresenter
import com.bownee.lenswave.gallery.GalleryThumbnailCacheIdentity
import com.bownee.lenswave.gallery.GalleryThumbnailCachePolicy
import com.bownee.lenswave.gallery.GalleryUiState
import com.bownee.lenswave.gallery.GalleryUpdatePresenter
import com.bownee.lenswave.gallery.GalleryViewModel
import com.bownee.lenswave.gallery.LibraryAction
import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.gallery.ProtonThumbnailImageSource
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import com.bownee.lenswave.viewer.PhotoViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.core.domain.entity.UserId
import me.proton.core.usersettings.domain.usecase.ObserveUserSettings
import me.proton.core.usersettings.domain.usecase.PerformUpdateTelemetry
import javax.inject.Inject

@AndroidEntryPoint
class GalleryActivity :
    FragmentActivity(),
    UpdateAvailableDialogFragment.Listener {
    @Inject lateinit var accountManager: AccountManager

    @Inject lateinit var authOrchestrator: AuthOrchestrator

    @Inject lateinit var thumbnailSource: ProtonThumbnailImageSource

    @Inject lateinit var photoDeletionExecutor: PhotoDeletionExecutor

    @Inject lateinit var observeUserSettings: ObserveUserSettings

    @Inject lateinit var updateTelemetry: PerformUpdateTelemetry

    @Inject lateinit var appUpdateChecker: AppUpdateChecker

    private val viewModel: GalleryViewModel by lazy {
        ViewModelProvider(this)[GalleryViewModel::class.java]
    }

    private lateinit var screen: GalleryScreen
    private val root get() = screen.root
    private val list get() = screen.list
    private val adapter get() = screen.adapter
    private val selectionBar get() = screen.selectionBar
    private lateinit var deletionCoordinator: GalleryDeletionCoordinator
    private lateinit var authCoordinator: GalleryAuthCoordinator
    private lateinit var settingsPresenter: GallerySettingsPresenter
    private lateinit var updatePresenter: GalleryUpdatePresenter

    private var currentUiState = GalleryUiState()
    private var renderedDestination: GalleryDestination? = null
    private var renderedContent: GalleryContent? = null
    private val scrollPositions = GalleryScrollPositionStore()
    private var pendingScrollRestore: GalleryDestination? = null
    private var safeBottom = 0
    private var thumbnailCacheIdentity: GalleryThumbnailCacheIdentity? = null

    // Registers an activity result launcher, so it must exist before the activity is started.
    private val notificationPermissionPrompter = GalleryNotificationPermissionPrompter(this)

    private val viewerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            if (data.getBooleanExtra(PhotoViewerActivity.EXTRA_PHOTO_DELETED, false) ||
                data.getBooleanExtra(PhotoViewerActivity.EXTRA_FAVORITE_CHANGED, false)
            ) {
                viewModel.refreshAfterMutation()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePresenter = GalleryUpdatePresenter(activity = this, appUpdateChecker = appUpdateChecker)
        updatePresenter.restore(savedInstanceState)
        configureEdgeToEdgeWindow()
        authCoordinator =
            GalleryAuthCoordinator(
                activity = this,
                accountManager = accountManager,
                authOrchestrator = authOrchestrator,
            )
        settingsPresenter =
            GallerySettingsPresenter(
                activity = this,
                observeUserSettings = observeUserSettings,
                updateTelemetry = updateTelemetry,
                currentUserId = { currentUiState.currentUserId },
                onConnectProton = ::connectProton,
                onDisconnectProton = viewModel::disconnectProton,
            )
        deletionCoordinator =
            GalleryDeletionCoordinator(
                activity = this,
                deletionExecutor = photoDeletionExecutor,
                currentUserId = { currentUiState.currentUserId },
                onSelectionCleared = { adapter.clearSelection() },
            )
        buildInterface()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )
        authCoordinator.register()
        observeGalleryState()
        if (savedInstanceState == null && LenswaveApplication.isAppUpdateStartupEnabled()) {
            updatePresenter.checkForUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeThumbnailDownloads()
        updatePresenter.showPendingUpdate()
    }

    override fun onDestroy() {
        if (this::screen.isInitialized) screen.dispose()
        authCoordinator.unregister()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        updatePresenter.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onUpdateRequested() = updatePresenter.onUpdateRequested()

    override fun onUpdateSnoozed(versionName: String) = updatePresenter.onUpdateSnoozed(versionName)

    private fun buildInterface() {
        screen =
            GalleryScreen(
                activity = this,
                scope = lifecycleScope,
                repository = thumbnailSource,
                currentUserId = { currentUiState.currentUserId },
                actions =
                    GalleryScreen.Actions(
                        onPhotoClicked = ::openPhoto,
                        onAlbumClicked = ::openAlbum,
                        onLibraryAction = ::performLibraryAction,
                        onSelectionChanged = ::showSelection,
                        onBack = ::navigateUp,
                        onSettings = ::showSettingsMenu,
                        onTabSelected = ::selectTab,
                        onFilterSelected = ::selectFilter,
                        onDeleteSelection = ::deleteSelectedPhotos,
                    ),
            )
        setContentView(screen.root)
        screen.onHeaderHeightChanged = { updateFastScrollTrack() }

        applySystemInsets()
        updateNavigationControls()
    }

    private fun observeGalleryState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.runPeriodicSync()
            }
        }
    }

    private fun render(state: GalleryUiState) {
        val destinationChanged = renderedDestination != state.destination
        val contentChanged = renderedContent != state.content
        if (destinationChanged) {
            renderedDestination?.let { previousDestination ->
                screen.captureScrollPosition()?.let { position ->
                    scrollPositions.save(previousDestination, position)
                }
            }
            pendingScrollRestore = state.destination
        }
        currentUiState = state
        notificationPermissionPrompter.requestIfNeeded(protonConnected = state.isProtonConnected)
        updateThumbnailCacheIdentity(state.currentUserId)
        renderedDestination = state.destination
        renderedContent = state.content
        if (contentChanged || destinationChanged) {
            when (val content = state.content) {
                is GalleryContent.Photos -> adapter.submitPhotos(content.assets)
                is GalleryContent.Library -> adapter.submitLibrary(content.sections)
            }
            restorePendingScrollPosition(state)
        }
        state.emptyState?.let { empty ->
            screen.showEmptyState(
                title = empty.title,
                message = empty.message,
                action = empty.actionLabel,
                onAction =
                    when (empty.action) {
                        GalleryEmptyAction.CONNECT_PROTON -> ::connectProton
                        null -> null
                    },
            )
        } ?: screen.showContent()
        updateNavigationControls()
        if (destinationChanged) {
            adapter.clearSelection()
        }
    }

    private fun updateThumbnailCacheIdentity(userId: UserId?) {
        val identity = GalleryThumbnailCacheIdentity(userId)
        if (GalleryThumbnailCachePolicy.shouldInvalidate(thumbnailCacheIdentity, identity)) {
            screen.adapter.clearThumbnails()
        }
        thumbnailCacheIdentity = identity
    }

    private fun selectDestination(destination: GalleryDestination) {
        clearSelectionForNavigation()
        viewModel.selectDestination(destination)
    }

    private fun openAlbum(album: ProtonAlbum) {
        clearSelectionForNavigation()
        viewModel.openAlbum(album)
    }

    /** Tapping the tab that is already showing scrolls it back to the top; otherwise its root opens. */
    private fun selectTab(tab: GalleryTab) {
        val destination = currentUiState.destination
        if (GalleryNavigationPolicy.tab(destination) == tab) {
            screen.scrollToTop()
            return
        }
        selectDestination(if (tab == GalleryTab.PHOTOS) GalleryDestination.Timeline else GalleryDestination.Library)
    }

    /** A filter chip switches the Photos tab to that media type; the active chip scrolls to the top. */
    private fun selectFilter(destination: GalleryDestination) {
        if (currentUiState.destination == destination) {
            screen.scrollToTop()
            return
        }
        selectDestination(destination)
    }

    private fun navigateUp() {
        clearSelectionForNavigation()
        viewModel.navigateUp()
    }

    private fun performLibraryAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.Open -> {
                selectDestination(action.destination)
            }

            is LibraryAction.Request -> {
                when (action.action) {
                    GalleryEmptyAction.CONNECT_PROTON -> connectProton()
                }
            }
        }
    }

    /** Drops any selection so it does not outlive the page being left. */
    private fun clearSelectionForNavigation() {
        adapter.clearSelection()
    }

    private fun connectProton() {
        authCoordinator.connectProton()
    }

    private fun showSettingsMenu() {
        settingsPresenter.showMenu(screen.settingsButton)
    }

    private fun openPhoto(photo: GalleryAsset) {
        val userId = currentUiState.currentUserId ?: return
        viewerLauncher.launch(
            PhotoViewerActivity.createIntent(this, photo, userId, currentUiState.visibleAssets),
        )
    }

    private fun showSelection(selected: List<GalleryAsset>) {
        screen.renderSelection(selectedCount = selected.size)
        updateNavigationControls()
    }

    private fun handleBack() {
        when {
            adapter.selectedPhotos().isNotEmpty() -> adapter.clearSelection()
            GalleryNavigationPolicy.parent(currentUiState.destination) != null -> navigateUp()
            else -> finish()
        }
    }

    private fun deleteSelectedPhotos() {
        deletionCoordinator.delete(adapter.selectedPhotos())
    }

    private fun updateNavigationControls() {
        val destination = currentUiState.destination
        val selecting = adapter.selectedPhotos().isNotEmpty()
        screen.renderNavigation(destination, currentUiState.title)
        // Every photo grid gets the draggable handle; the album list uses the platform scrollbar.
        list.setFastScrollHandleEnabled(currentUiState.content is GalleryContent.Photos)
        updateGalleryFooterHeight(selecting)
    }

    private fun updateGalleryFooterHeight(selecting: Boolean) {
        screen.setFooterHeight(
            GalleryFastScrollLayoutPolicy.footerHeight(
                selectionBarVisible = selecting,
                bottomInset = safeBottom,
                selectionClearance = dp(GalleryScreen.SELECTION_BAR_HEIGHT_DP + 20),
                baseClearance = dp(12),
            ),
        )
    }

    private fun restorePendingScrollPosition(state: GalleryUiState) {
        val destination = pendingScrollRestore ?: return
        if (destination != state.destination) return
        val savedPosition = scrollPositions.positionFor(destination)
        if (savedPosition != null &&
            savedPosition.firstVisiblePosition > 0 &&
            adapter.count == 0 &&
            state.emptyState == null
        ) {
            return
        }

        pendingScrollRestore = null
        screen.restoreScrollPosition(
            savedPosition ?: GalleryScrollPosition(firstVisiblePosition = 0, topOffset = 0),
        ) { currentUiState.destination == destination }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeArea: Insets =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            safeBottom = safeArea.bottom
            screen.applySafeArea(safeArea)
            updateFastScrollTrack()
            updateGalleryFooterHeight(adapter.selectedPhotos().isNotEmpty())
            selectionBar.applyBottomOverlayInsets(safeArea)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** The handle travels between the pinned header and the navigation bar with the same gap at both ends. */
    private fun updateFastScrollTrack() {
        val gap = resources.getDimensionPixelSize(R.dimen.gallery_fast_scroll_edge_margin)
        list.setFastScrollEdgeInsets(top = screen.headerHeight + gap, bottom = safeBottom + gap)
    }
}
