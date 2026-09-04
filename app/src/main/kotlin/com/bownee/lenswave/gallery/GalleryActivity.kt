package com.bownee.lenswave.gallery

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.bownee.lenswave.gallery.GalleryAlbumTile
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryAuthCoordinator
import com.bownee.lenswave.gallery.GalleryContent
import com.bownee.lenswave.gallery.GalleryDeletionCoordinator
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.GalleryEmptyAction
import com.bownee.lenswave.gallery.GalleryFastScrollLayoutPolicy
import com.bownee.lenswave.gallery.GalleryGrouping
import com.bownee.lenswave.gallery.GalleryMutationEvent
import com.bownee.lenswave.gallery.GalleryNavigationPolicy
import com.bownee.lenswave.gallery.GalleryNotificationPermissionPrompter
import com.bownee.lenswave.gallery.GalleryRowSet
import com.bownee.lenswave.gallery.GalleryScrollPosition
import com.bownee.lenswave.gallery.GallerySettingsPresenter
import com.bownee.lenswave.gallery.GalleryThumbnailCacheIdentity
import com.bownee.lenswave.gallery.GalleryThumbnailCachePolicy
import com.bownee.lenswave.gallery.GalleryUiState
import com.bownee.lenswave.gallery.GalleryUpdatePresenter
import com.bownee.lenswave.gallery.GalleryViewModel
import com.bownee.lenswave.gallery.LibraryAction
import com.bownee.lenswave.gallery.ProtonThumbnailImageSource
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import com.bownee.lenswave.viewer.PhotoViewerActivity
import com.bownee.lenswave.viewer.ViewerMutationCoordinator
import com.bownee.lenswave.viewer.ViewerPrivacySettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.core.domain.entity.UserId
import me.proton.core.usersettings.domain.usecase.ObserveUserSettings
import me.proton.core.usersettings.domain.usecase.PerformUpdateTelemetry
import javax.inject.Inject

@AndroidEntryPoint
class GalleryActivity :
    FragmentActivity(),
    UpdateAvailableDialogFragment.Listener,
    TrashConfirmationDialogFragment.Listener,
    PrivacySettingsDialogFragment.Listener,
    DisconnectProtonDialogFragment.Listener {
    @Inject lateinit var accountManager: AccountManager

    @Inject lateinit var authOrchestrator: AuthOrchestrator

    @Inject lateinit var thumbnailSource: ProtonThumbnailImageSource

    @Inject lateinit var observeUserSettings: ObserveUserSettings

    @Inject lateinit var updateTelemetry: PerformUpdateTelemetry

    @Inject lateinit var appUpdateChecker: AppUpdateChecker

    @Inject lateinit var viewerPrivacySettings: ViewerPrivacySettings

    @Inject lateinit var mutationCoordinator: ViewerMutationCoordinator

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

    // The panel starts hidden, which is what a null empty state renders, so the first render can skip it.
    private var renderedEmptyState: GalleryEmptyState? = null

    // Day headers depend on the device zone; a zone or clock change bumps this so the rows are regrouped.
    private val groupingGeneration = MutableStateFlow(0)
    private var renderedGrouping = 0
    private var dayLabels: GalleryGrouping.DayLabels? = null
    private var dayLabelsGeneration = 0
    private val timeChangeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                groupingGeneration.update { generation -> generation + 1 }
            }
        }
    private var timeChangeReceiverRegistered = false
    private var pendingScrollRestore: GalleryDestination? = null
    private var pendingSelectionRestore = false
    private var viewerLaunched = false
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
        // Set before any content: the thumbnails are the same photos the viewer keeps out of screenshots.
        applyScreenshotPolicy()
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
                privacySettings = viewerPrivacySettings,
                onConnectProton = ::connectProton,
                onDisconnectProton = viewModel::disconnectProton,
            )
        deletionCoordinator = GalleryDeletionCoordinator(activity = this)
        buildInterface()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )
        authCoordinator.register()
        registerTimeChangeReceiver()
        observeGalleryState()
        // Every instance asks; the checker runs the check once per process and hands its result out once.
        if (LenswaveApplication.isAppUpdateStartupEnabled()) updatePresenter.checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // The setting is toggled from this screen's own menu; it takes effect without a recreation.
        applyScreenshotPolicy()
        viewerLaunched = false
        // The collector below saw these while the viewer was still launched; now they are the gallery's.
        consumeViewerOutcomes(mutationCoordinator.outcomes.value)
        viewModel.resumeThumbnailDownloads()
        updatePresenter.showPendingUpdate()
    }

    override fun onDestroy() {
        if (this::screen.isInitialized) screen.dispose()
        if (this::settingsPresenter.isInitialized) settingsPresenter.dispose()
        if (timeChangeReceiverRegistered) unregisterReceiver(timeChangeReceiver)
        if (this::authCoordinator.isInitialized) authCoordinator.unregister()
        super.onDestroy()
    }

    /** Mirrors [ViewerPrivacySettings.blockScreenshots] into the window's secure flag. */
    private fun applyScreenshotPolicy() {
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        if (viewerPrivacySettings.blockScreenshots) window.addFlags(secure) else window.clearFlags(secure)
    }

    /**
     * System broadcasts reach a non-exported receiver; the platform resets the default zone before
     * sending. The flag keeps onDestroy from unregistering a receiver an onCreate that failed
     * before this point never registered.
     */
    private fun registerTimeChangeReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            }
        ContextCompat.registerReceiver(this, timeChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        timeChangeReceiverRegistered = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        updatePresenter.save(outState)
        saveScrollPosition()
        super.onSaveInstanceState(outState)
    }

    /** Hands the position of the page on screen to the view model, which outlives this activity. */
    private fun saveScrollPosition() {
        val destination = renderedDestination ?: return
        // A restore still pending means the list does not show that page's position yet.
        if (pendingScrollRestore == destination) return
        screen.captureScrollPosition()?.let { position -> viewModel.saveScrollPosition(destination, position) }
    }

    override fun onUpdateRequested() = updatePresenter.onUpdateRequested()

    override fun onUpdateSnoozed(versionName: String) = updatePresenter.onUpdateSnoozed(versionName)

    override fun onTrashConfirmed(nodeUids: List<String>) = viewModel.trashPhotos(nodeUids)

    override fun onTelemetryPreferenceSaved(enabled: Boolean) = settingsPresenter.saveTelemetryPreference(enabled)

    override fun onDisconnectProtonConfirmed() = settingsPresenter.disconnectProtonConfirmed()

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
                        onRefresh = { viewModel.requestRefresh(manual = true) },
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
                combine(viewModel.uiState, groupingGeneration) { state, generation -> state to generation }
                    .collectLatest { (state, generation) -> render(state, generation) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.runPeriodicSync()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mutationEvents.collect { event ->
                    if (event is GalleryMutationEvent.Trashed) adapter.clearSelection()
                    deletionCoordinator.showOutcome(event)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mutationCoordinator.outcomes.collect(::consumeViewerOutcomes)
            }
        }
    }

    /**
     * Takes the viewer's mutation outcomes nobody consumed (see [GalleryViewerOutcomePolicy]): a
     * favourite or trash the user started in the viewer and backed out of before it finished.
     * While a viewer launched from here is up, its own collector owns them.
     */
    private fun consumeViewerOutcomes(outcomes: List<ViewerMutationCoordinator.Outcome>) {
        if (!GalleryViewerOutcomePolicy.consumesNow(viewerLaunched, outcomes)) return
        val summary = GalleryViewerOutcomePolicy.summarize(outcomes)
        outcomes.forEach(mutationCoordinator::consume)
        if (summary.refresh) viewModel.refreshAfterMutation()
        if (summary.failedFavorite) Toast.makeText(this, R.string.could_not_update_favorite, Toast.LENGTH_LONG).show()
        if (summary.failedTrash) Toast.makeText(this, R.string.could_not_move_to_proton_trash, Toast.LENGTH_LONG).show()
    }

    /**
     * Collected with collectLatest: a newer state cancels a render still building its rows. Only
     * the cheap, idempotent parts run before that suspension; everything the rest of the activity
     * reads back ([currentUiState], the rendered destination and content, the pending scroll
     * restore) is written after the rows are on screen, so a cancelled render leaves no record of
     * a page the grid never showed, and the newer state's render always submits its rows.
     */
    private suspend fun render(
        state: GalleryUiState,
        grouping: Int,
    ) {
        val destinationChanged = renderedDestination != state.destination
        val contentChanged =
            GalleryRenderPolicy.contentChanged(renderedContent, state.content) || renderedGrouping != grouping
        screen.setRefreshing(state.isRefreshing)
        notificationPermissionPrompter.requestIfNeeded(protonConnected = state.isProtonConnected)
        updateThumbnailCacheIdentity(state.currentUserId)
        if (contentChanged || destinationChanged) {
            val rows = buildRows(state.content, grouping)
            if (destinationChanged) {
                // The list still shows the previous page here, so its position can be captured.
                saveScrollPosition()
                pendingScrollRestore = state.destination
                pendingSelectionRestore = true
            }
            adapter.submitRows(rows)
            renderedDestination = state.destination
            renderedContent = state.content
            renderedGrouping = grouping
        }
        currentUiState = state
        if (contentChanged || destinationChanged) {
            restorePendingScrollPosition(state)
            restorePendingSelection(state)
        }
        renderEmptyState(state.emptyState)
        updateNavigationControls()
    }

    /**
     * Applies the view model's selection to the grid: empty after a navigation, or the selection
     * kept across a recreation once the page's rows are there to carry it.
     */
    private fun restorePendingSelection(state: GalleryUiState) {
        if (!pendingSelectionRestore) return
        val selection = viewModel.selectedStableIds
        if (selection.isNotEmpty() && adapter.count == 0 && state.emptyState == null) return
        pendingSelectionRestore = false
        adapter.setSelection(selection)
    }

    /** The panel is small but re-applying it (three texts and a listener) on every publish is not free. */
    private fun renderEmptyState(emptyState: GalleryEmptyState?) {
        if (renderedEmptyState == emptyState) return
        renderedEmptyState = emptyState
        emptyState?.let { empty ->
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
    }

    /**
     * Photo pages are grouped on a worker thread: the cost scales with the number of day groups
     * (a label each on a cold cache) as much as with the assets, and neither is known up front.
     * The empty page and the small library page are built inline.
     */
    private suspend fun buildRows(
        content: GalleryContent,
        grouping: Int,
    ): GalleryRowSet =
        when (content) {
            is GalleryContent.Library -> {
                GalleryRowSet.of(GalleryGrouping.createLibraryRows(content.sections, describeAlbum = ::describeAlbum))
            }

            is GalleryContent.Photos -> {
                if (content.assets.isEmpty()) return GalleryRowSet.EMPTY
                val unknownDateLabel = getString(R.string.unknown_date)
                val dayLabels = dayLabelsFor(grouping)
                withContext(Dispatchers.Default) {
                    GalleryRowSet.of(
                        GalleryGrouping.createRows(
                            content.assets,
                            unknownDateLabel = unknownDateLabel,
                            dayLabels = dayLabels,
                        ),
                    )
                }
            }
        }

    /** The texts an album cell shows, resolved once per row build instead of on every bind. */
    private fun describeAlbum(album: ProtonAlbum): GalleryAlbumTile {
        val name = album.name.ifBlank { getString(R.string.untitled_album) }
        val photoCount = resources.getQuantityString(R.plurals.photo_count, album.photoCount.toInt(), album.photoCount)
        val details = if (album.isShared) getString(R.string.shared_album_details, photoCount) else photoCount
        return GalleryAlbumTile(
            album = album,
            name = name,
            details = details,
            contentDescription = getString(R.string.album_accessibility, name, details),
        )
    }

    /** Day labels are cached across renders and dropped when the grouping generation moves on. */
    private fun dayLabelsFor(grouping: Int): GalleryGrouping.DayLabels {
        if (dayLabels == null || dayLabelsGeneration != grouping) {
            dayLabels = GalleryGrouping.DayLabels()
            dayLabelsGeneration = grouping
        }
        return requireNotNull(dayLabels)
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

    /**
     * A second tap before the viewer is up must not open a second viewer; the guard lifts on resume.
     * [index] is the tapped photo's position in [GalleryUiState.visibleAssets], known to the cell that
     * was tapped; a page that changed under the tap is caught by checking the asset at that index.
     */
    private fun openPhoto(
        photo: GalleryAsset,
        index: Int,
    ) {
        val userId = currentUiState.currentUserId ?: return
        if (viewerLaunched) return
        viewerLaunched = true
        val assets = currentUiState.visibleAssets
        // Verified here so createIntent can take the index instead of searching the list once its
        // signature (viewer-owned) accepts one; until then it still locates the photo itself.
        val tapped = assets.getOrNull(index)?.takeIf { it.stableId == photo.stableId } ?: photo
        viewerLauncher.launch(
            PhotoViewerActivity.createIntent(this, tapped, userId, assets, currentUiState.destination),
        )
    }

    private fun showSelection(selected: List<GalleryAsset>) {
        viewModel.setSelection(selected.mapTo(LinkedHashSet(selected.size)) { it.stableId })
        screen.renderSelection(selectedCount = selected.size)
        updateNavigationControls()
    }

    private fun handleBack() {
        when {
            adapter.hasSelection() -> adapter.clearSelection()
            GalleryNavigationPolicy.parent(currentUiState.destination) != null -> navigateUp()
            else -> finish()
        }
    }

    private fun deleteSelectedPhotos() {
        deletionCoordinator.delete(adapter.selectedPhotos())
    }

    private fun updateNavigationControls() {
        val destination = currentUiState.destination
        val selecting = adapter.hasSelection()
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

    /**
     * A page without rows is still loading unless its empty panel is up: restoring into it would
     * clamp the saved position to the top and lose it. The guard rests on the adapter alone, not
     * on the identity of the empty content instance the factory happens to publish.
     */
    private fun restorePendingScrollPosition(state: GalleryUiState) {
        val destination = pendingScrollRestore ?: return
        if (destination != state.destination) return
        if (adapter.count == 0 && state.emptyState == null) return

        val savedPosition = viewModel.scrollPositions.positionFor(destination)
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
            updateGalleryFooterHeight(adapter.hasSelection())
            selectionBar.applyBottomOverlayInsets(safeArea)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** The handle travels between the pinned header and the navigation bar with the same gap at both ends. */
    private fun updateFastScrollTrack() {
        list.setFastScrollEdgeInsets(
            top = screen.headerHeight + fastScrollEdgeGap,
            bottom =
                safeBottom + fastScrollEdgeGap,
        )
    }

    /** Resolved once: the header relayouts that call [updateFastScrollTrack] must not each hit the resources. */
    private val fastScrollEdgeGap: Int by lazy(LazyThreadSafetyMode.NONE) {
        resources.getDimensionPixelSize(R.dimen.gallery_fast_scroll_edge_margin)
    }
}
