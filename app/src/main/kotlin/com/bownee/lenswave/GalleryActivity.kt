package com.bownee.lenswave

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bownee.lenswave.gallery.DeviceAccessLevel
import com.bownee.lenswave.gallery.DevicePermissionPolicy
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryContent
import com.bownee.lenswave.gallery.GalleryDeletionCoordinator
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.GalleryEmptyAction
import com.bownee.lenswave.gallery.GalleryGrouping
import com.bownee.lenswave.gallery.GalleryFastScrollLayoutPolicy
import com.bownee.lenswave.gallery.GalleryScrollPosition
import com.bownee.lenswave.gallery.GallerySource
import com.bownee.lenswave.gallery.GalleryScrollPositionStore
import com.bownee.lenswave.gallery.GalleryNavigationPolicy
import com.bownee.lenswave.gallery.GalleryThumbnailCacheIdentity
import com.bownee.lenswave.gallery.GalleryThumbnailCachePolicy
import com.bownee.lenswave.gallery.GalleryUiState
import com.bownee.lenswave.gallery.GalleryViewModel
import com.bownee.lenswave.gallery.LibraryAction
import com.bownee.lenswave.gallery.ThumbnailNotificationPermissionPolicy
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonPhotoGateway
import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.proton.ProtonPresentationInitializer
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.core.domain.entity.UserId
import me.proton.core.usersettings.domain.usecase.ObserveUserSettings
import me.proton.core.usersettings.domain.usecase.PerformUpdateTelemetry
import javax.inject.Inject

@AndroidEntryPoint
class GalleryActivity : FragmentActivity(), UpdateAvailableDialogFragment.Listener {
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var authOrchestrator: AuthOrchestrator
    @Inject lateinit var protonRepository: ProtonPhotoGateway
    @Inject lateinit var photoDeletionExecutor: PhotoDeletionExecutor
    @Inject lateinit var observeUserSettings: ObserveUserSettings
    @Inject lateinit var updateTelemetry: PerformUpdateTelemetry
    @Inject lateinit var appUpdateChecker: AppUpdateChecker

    private val viewModel: GalleryViewModel by lazy {
        ViewModelProvider(this)[GalleryViewModel::class.java]
    }

    private lateinit var screen: GalleryScreen
    private val root get() = screen.root
    private val galleryHeader get() = screen.galleryHeader
    private val list get() = screen.list
    private val galleryFooter get() = screen.galleryFooter
    private val adapter get() = screen.adapter
    private val tabBar get() = screen.tabBar
    private val selectionBar get() = screen.selectionBar
    private val settingsButton get() = screen.settingsButton
    private val trashDeleteAllButton get() = screen.trashDeleteAllButton
    private lateinit var deletionCoordinator: GalleryDeletionCoordinator

    private var currentUiState = GalleryUiState()
    private var renderedDestination: GalleryDestination? = null
    private var renderedContent: GalleryContent? = null
    private val scrollPositions = GalleryScrollPositionStore()
    private var pendingScrollRestore: GalleryDestination? = null
    private var safeBottom = 0
    private var visibleAssets: List<GalleryAsset> = emptyList()
    private var fastScrollEdgePadding = 0
    private var pendingUpdateVersionName: String? = null
    private var thumbnailCacheIdentity: GalleryThumbnailCacheIdentity? = null
    private var notificationPermissionRequestInFlight = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateDeviceAccess()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionRequestInFlight = false
        getSharedPreferences("permissions", MODE_PRIVATE).edit {
            putBoolean(KEY_THUMBNAIL_NOTIFICATION_PERMISSION_REQUESTED, true)
        }
    }

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        if (data.getBooleanExtra(PhotoViewerActivity.EXTRA_PHOTO_DELETED, false)) {
            viewModel.refreshAfterMutation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUpdateVersionName = savedInstanceState?.getString(STATE_PENDING_UPDATE_VERSION)
        configureWindow()
        deletionCoordinator = GalleryDeletionCoordinator(
            activity = this,
            deletionExecutor = photoDeletionExecutor,
            currentUserId = { currentUiState.currentUserId },
            onSelectionCleared = { adapter.clearSelection() },
            onDevicePhotosChanged = viewModel::refreshAfterMutation,
            savedState = savedInstanceState?.getBundle(STATE_DELETION),
        )
        buildInterface()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        initializeAuthentication()
        observeGalleryState()
        updateDeviceAccess()
        if (savedInstanceState == null && LenswaveApplication.isAppUpdateStartupEnabled()) {
            checkForAppUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDeviceAccess()
        viewModel.resumeThumbnailDownloads()
        showPendingUpdate()
    }

    override fun onDestroy() {
        if (this::screen.isInitialized) screen.dispose()
        authOrchestrator.unregister()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(STATE_DELETION, deletionCoordinator.saveState())
        pendingUpdateVersionName?.let { outState.putString(STATE_PENDING_UPDATE_VERSION, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onUpdateRequested() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, LATEST_RELEASE_PAGE_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser_for_update, Toast.LENGTH_LONG).show()
        }
    }

    override fun onUpdateSnoozed(versionName: String) {
        appUpdateChecker.snooze(versionName)
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildInterface() {
        screen = GalleryScreen(
            activity = this,
            scope = lifecycleScope,
            repository = protonRepository,
            currentUserId = { currentUiState.currentUserId },
            currentDestination = { currentUiState.destination },
            actions = GalleryScreen.Actions(
                onPhotoClicked = ::openPhoto,
                onFavoriteClicked = ::toggleOverviewFavorite,
                onAlbumClicked = ::openAlbum,
                onLibraryAction = ::performLibraryAction,
                onSelectionChanged = ::showSelection,
                onRefresh = {
                    viewModel.requestRefresh()
                },
                onBack = ::navigateUp,
                onSettings = ::showSettingsMenu,
                onDeleteAllTrash = ::confirmDeleteAllTrashPhotos,
                onPhotosTab = ::openPhotosTab,
                onLibraryTab = ::openLibrary,
                onSourceSelected = ::selectSource,
                onDeleteSelection = ::deleteSelectedPhotos,
            ),
        )
        setContentView(screen.root)

        applySystemInsets()
        updateNavigationControls()
    }

    private fun initializeAuthentication() {
        ProtonPresentationInitializer.registerAuthentication(
            activity = this,
            accountManager = accountManager,
            authOrchestrator = authOrchestrator,
            onAuthenticationError = ::showAuthenticationError,
        )
        authOrchestrator.setOnLoginResult { }
    }

    private fun initializeProtonCore() {
        ProtonPresentationInitializer.initializeCore(applicationContext)
    }

    private fun checkForAppUpdate() {
        lifecycleScope.launch {
            val update = appUpdateChecker.findAvailableUpdate(BuildConfig.VERSION_NAME) ?: return@launch
            pendingUpdateVersionName = update.versionName
            showPendingUpdate()
        }
    }

    private fun showPendingUpdate() {
        val versionName = pendingUpdateVersionName ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(UpdateAvailableDialogFragment.TAG) != null) {
            pendingUpdateVersionName = null
            return
        }
        UpdateAvailableDialogFragment.create(versionName, BuildConfig.VERSION_NAME).show(
            supportFragmentManager,
            UpdateAvailableDialogFragment.TAG,
        )
        pendingUpdateVersionName = null
    }


    private fun observeGalleryState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest(::render)
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
        requestThumbnailNotificationPermissionIfNeeded(state)
        updateThumbnailCacheIdentity(deviceAccessLevel(), state.currentUserId)
        renderedDestination = state.destination
        renderedContent = state.content
        if (contentChanged || destinationChanged) {
            when (val content = state.content) {
                is GalleryContent.Photos -> submitPhotos(content.assets)
                is GalleryContent.Library -> {
                    visibleAssets = emptyList()
                    adapter.submitLibrary(content.sections)
                }
            }
            restorePendingScrollPosition(state)
        }
        state.emptyState?.let { empty ->
            screen.showEmptyState(
                title = empty.title,
                message = empty.message,
                action = empty.actionLabel,
                onAction = when (empty.action) {
                    GalleryEmptyAction.CONNECT_PROTON -> ::connectProton
                    GalleryEmptyAction.REQUEST_DEVICE_ACCESS -> ::requestDeviceAccess
                    null -> null
                },
            )
        } ?: screen.showContent()
        screen.renderHeader(
            statusText = state.statusText,
            showDeleteAll = state.showDeleteAll && adapter.selectedPhotos().isEmpty(),
            refreshing = state.isRefreshing,
        )
        updateNavigationControls()
        if (destinationChanged) {
            adapter.clearSelection()
        }
    }

    private fun requestDeviceAccess() {
        val preferences = getSharedPreferences("permissions", MODE_PRIVATE)
        val requestedBefore = preferences.getBoolean("device-photos-requested", false)
        val showRationale = devicePermissions().any(::shouldShowRequestPermissionRationale)
        if (requestedBefore && !showRationale && deviceAccessLevel() == DeviceAccessLevel.NONE) {
            AlertDialog.Builder(this)
                .setTitle(R.string.allow_photo_access_settings)
                .setMessage(R.string.allow_photo_access_settings_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_settings) { _, _ -> openApplicationSettings() }
                .show()
            return
        }
        val launchRequest = {
            preferences.edit { putBoolean("device-photos-requested", true) }
            permissionLauncher.launch(devicePermissions())
        }
        if (showRationale) {
            AlertDialog.Builder(this)
                .setTitle(R.string.allow_photo_access)
                .setMessage(R.string.photo_access_rationale)
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.continue_action) { _, _ -> launchRequest() }
                .show()
        } else {
            launchRequest()
        }
    }

    private fun requestThumbnailNotificationPermissionIfNeeded(state: GalleryUiState) {
        val preferences = getSharedPreferences("permissions", MODE_PRIVATE)
        if (!ThumbnailNotificationPermissionPolicy.shouldRequest(
                apiLevel = Build.VERSION.SDK_INT,
                protonConnected = state.isProtonConnected,
                permissionGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
                requestedBefore = preferences.getBoolean(
                    KEY_THUMBNAIL_NOTIFICATION_PERMISSION_REQUESTED,
                    false,
                ) || notificationPermissionRequestInFlight,
            )
        ) return
        notificationPermissionRequestInFlight = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun updateDeviceAccess() {
        val accessLevel = deviceAccessLevel()
        updateThumbnailCacheIdentity(accessLevel, currentUiState.currentUserId)
        viewModel.setDeviceAccess(accessLevel)
    }

    private fun updateThumbnailCacheIdentity(
        accessLevel: DeviceAccessLevel,
        userId: UserId?,
    ) {
        val identity = GalleryThumbnailCacheIdentity(accessLevel, userId)
        if (GalleryThumbnailCachePolicy.shouldInvalidate(thumbnailCacheIdentity, identity)) {
            screen.adapter.clearThumbnails()
        }
        thumbnailCacheIdentity = identity
    }

    @SuppressLint("InlinedApi") // Permission strings are queried only by the API-aware policy.
    private fun deviceAccessLevel(): DeviceAccessLevel = DevicePermissionPolicy.accessLevel(
        apiLevel = Build.VERSION.SDK_INT,
        readMediaImagesGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED,
        readMediaVideosGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) == PackageManager.PERMISSION_GRANTED,
        selectedPhotosGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED,
        legacyReadGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED,
    )

    private fun openApplicationSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    private fun managePhotoAccess() {
        if (DevicePermissionPolicy.shouldOpenSettingsToManage(deviceAccessLevel())) {
            openApplicationSettings()
        } else {
            requestDeviceAccess()
        }
    }

    private fun photoAccessMenuTitle(): String {
        val accessLabel = when (deviceAccessLevel()) {
            DeviceAccessLevel.NONE -> R.string.photo_access_none
            DeviceAccessLevel.PARTIAL -> R.string.photo_access_selected
            DeviceAccessLevel.FULL -> R.string.photo_access_all
        }
        return getString(R.string.photo_access_state, getString(accessLabel))
    }

    private fun selectDestination(destination: GalleryDestination) {
        beforeNavigation()
        viewModel.selectDestination(destination)
    }

    private fun openAlbum(album: ProtonAlbum) {
        beforeNavigation()
        viewModel.openAlbum(album)
    }

    private fun openPhotosTab() {
        beforeNavigation()
        viewModel.openPhotosTab()
    }

    private fun openLibrary() {
        beforeNavigation()
        viewModel.openLibrary()
    }

    private fun selectSource(source: GallerySource) {
        beforeNavigation()
        viewModel.selectSource(source)
    }

    private fun navigateUp() {
        beforeNavigation()
        viewModel.navigateUp()
    }

    private fun performLibraryAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.Open -> selectDestination(action.destination)
            is LibraryAction.Request -> when (action.action) {
                GalleryEmptyAction.CONNECT_PROTON -> connectProton()
                GalleryEmptyAction.REQUEST_DEVICE_ACCESS -> requestDeviceAccess()
            }
        }
    }

    private fun beforeNavigation() {
        adapter.clearSelection()
        trashDeleteAllButton.visibility = View.GONE
    }

    private fun connectProton() {
        initializeProtonCore()
        authOrchestrator.startLoginWorkflow()
    }

    private fun showSettingsMenu() {
        PopupMenu(this, settingsButton).apply {
            if (currentUiState.currentUserId == null) {
                menu.add(android.view.Menu.NONE, SETTINGS_CONNECT_PROTON, 0, R.string.connect_proton)
            } else {
                menu.add(android.view.Menu.NONE, SETTINGS_DISCONNECT_PROTON, 0, R.string.disconnect_proton)
            }
            menu.add(android.view.Menu.NONE, SETTINGS_PHOTO_ACCESS, 1, photoAccessMenuTitle())
            menu.add(android.view.Menu.NONE, SETTINGS_PRIVACY, 2, R.string.privacy_and_data)
            menu.add(
                android.view.Menu.NONE,
                android.view.Menu.NONE,
                3,
                getString(R.string.app_version, BuildConfig.VERSION_NAME),
            ).isEnabled = false
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    SETTINGS_CONNECT_PROTON -> connectProton()
                    SETTINGS_DISCONNECT_PROTON -> confirmDisconnectProton()
                    SETTINGS_PHOTO_ACCESS -> managePhotoAccess()
                    SETTINGS_PRIVACY -> showPrivacySettings()
                }
                true
            }
            show()
        }
    }

    private fun showPrivacySettings() {
        val userId = currentUiState.currentUserId
        if (userId == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_and_data)
                .setMessage(R.string.privacy_disconnected_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        lifecycleScope.launch {
            val enabled = runCatching { observeUserSettings(userId, false).first()?.telemetry == true }
                .getOrDefault(false)
            var desired = enabled
            AlertDialog.Builder(this@GalleryActivity)
                .setTitle(R.string.privacy_and_data)
                .setMessage(R.string.privacy_connected_message)
                .setMultiChoiceItems(
                    arrayOf(getString(R.string.allow_proton_telemetry)),
                    booleanArrayOf(enabled),
                ) { _, _, checked -> desired = checked }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save) { _, _ ->
                    lifecycleScope.launch {
                        val updated = runCatching { updateTelemetry(userId, desired) }.isSuccess
                        Toast.makeText(
                            this@GalleryActivity,
                            if (updated) R.string.privacy_setting_saved else R.string.privacy_setting_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .show()
        }
    }

    private fun confirmDisconnectProton() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disconnect_proton_question)
            .setMessage(R.string.disconnect_proton_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disconnect) { _, _ -> disconnectProton() }
            .show()
    }

    private fun disconnectProton() {
        viewModel.disconnectProton()
    }

    private fun openPhoto(photo: GalleryAsset) {
        viewerLauncher.launch(
            PhotoViewerActivity.createIntent(this, photo, currentUiState.currentUserId, visibleAssets)
        )
    }

    private fun showSelection(selected: List<GalleryAsset>) {
        val selecting = selected.isNotEmpty()
        val viewingTrash = currentUiState.isTrash
        screen.renderSelection(
            selectedCount = selected.size,
            viewingTrash = viewingTrash,
            showDeleteAll = currentUiState.showDeleteAll && visibleAssets.isNotEmpty(),
        )
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
        deletionCoordinator.delete(adapter.selectedPhotos(), permanently = currentUiState.isTrash)
    }

    private fun confirmDeleteAllTrashPhotos() {
        deletionCoordinator.deleteAllFromTrash(visibleAssets)
    }
    private fun submitPhotos(photos: List<GalleryAsset>) {
        visibleAssets = GalleryGrouping.sortPhotos(photos)
        adapter.submitPhotos(visibleAssets)
    }

    private fun devicePermissions(): Array<String> = DevicePermissionPolicy.permissions(Build.VERSION.SDK_INT)

    private fun updateNavigationControls() {
        val destination = currentUiState.destination
        val selecting = adapter.selectedPhotos().isNotEmpty()
        screen.renderNavigation(
            title = currentUiState.title,
            tab = GalleryNavigationPolicy.tab(destination),
            showBack = GalleryNavigationPolicy.parent(destination) != null,
            sources = if (selecting) {
                emptyList()
            } else {
                GalleryNavigationPolicy.sources(
                    destination,
                    supportsDeviceTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                )
            },
            selectedSource = GalleryNavigationPolicy.selectedSource(destination),
        )
        updateNavigationVisibility(selecting)
    }

    private fun updateNavigationVisibility(selecting: Boolean) {
        tabBar.visibility = if (selecting) View.GONE else View.VISIBLE
        updateGalleryFooterHeight()
    }

    private fun updateGalleryFooterHeight() {
        val height = GalleryFastScrollLayoutPolicy.footerHeight(
            navigationVisible = tabBar.isVisible,
            bottomInset = safeBottom,
            navigationClearance = dp(74),
            baseClearance = dp(12),
        )
        val params = galleryFooter.layoutParams ?: AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
        if (params.height == height) return
        params.height = height
        galleryFooter.layoutParams = params
    }

    private fun toggleOverviewFavorite(photo: GalleryAsset) {
        val userId = currentUiState.currentUserId ?: return
        if (!photo.canFavoriteInProton) return
        val nodeUids = photo.protonReplicaNodeUids.distinct()
        if (nodeUids.isEmpty()) return
        val favorite = !photo.isFavorite
        adapter.beginFavoriteUpdate(photo.stableId, favorite)
        lifecycleScope.launch {
            var succeeded = false
            try {
                val result = protonRepository.setFavorite(userId, nodeUids, favorite)
                succeeded = result.updatedCount == nodeUids.size
                if (!succeeded) {
                    Toast.makeText(
                        this@GalleryActivity,
                        R.string.could_not_update_favorite,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Toast.makeText(
                    this@GalleryActivity,
                    R.string.could_not_update_favorite,
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                adapter.finishFavoriteUpdate(photo.stableId, succeeded)
            }
        }
    }

    private fun restorePendingScrollPosition(state: GalleryUiState) {
        val destination = pendingScrollRestore ?: return
        if (destination != state.destination) return
        val savedPosition = scrollPositions.positionFor(destination)
        if (savedPosition != null &&
            savedPosition.firstVisiblePosition > 0 &&
            adapter.count == 0 &&
            state.emptyState == null
        ) return

        pendingScrollRestore = null
        screen.restoreScrollPosition(
            savedPosition ?: GalleryScrollPosition(firstVisiblePosition = 0, topOffset = 0),
        ) { currentUiState.destination == destination }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeArea: Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeBottom = safeArea.bottom
            fastScrollEdgePadding = GalleryFastScrollLayoutPolicy.edgePadding(
                topInset = safeArea.top,
                bottomInset = safeArea.bottom,
                margin = resources.getDimensionPixelSize(R.dimen.gallery_fast_scroll_edge_margin),
            )
            list.setPadding(0, safeArea.top, 0, 0)
            list.setFastScrollEdgeInset(fastScrollEdgePadding)
            galleryHeader.setPadding(
                dp(16) + safeArea.left,
                dp(10),
                dp(16) + safeArea.right,
                dp(10),
            )
            updateNavigationVisibility(adapter.selectedPhotos().isNotEmpty())
            screen.updateStickyDateMargins(
                top = dp(8) + safeArea.top,
                start = dp(8) + if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    safeArea.right
                } else {
                    safeArea.left
                },
            )
            updateBottomOverlayInsets(tabBar, safeArea)
            updateBottomOverlayInsets(selectionBar, safeArea)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateBottomOverlayInsets(view: View, insets: Insets) {
        (view.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = dp(8) + insets.left
            rightMargin = dp(8) + insets.right
            bottomMargin = dp(8) + insets.bottom
            view.layoutParams = this
        }
    }

    private fun showAuthenticationError() {
        Toast.makeText(this, R.string.proton_unlock_failed, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val LATEST_RELEASE_PAGE_URL = "https://github.com/Bownee/Lenswave/releases/latest"
        const val STATE_DELETION = "gallery.deletion"
        const val STATE_PENDING_UPDATE_VERSION = "gallery.pending-update-version"
        const val SETTINGS_CONNECT_PROTON = 1
        const val SETTINGS_DISCONNECT_PROTON = 2
        const val SETTINGS_PHOTO_ACCESS = 3
        const val SETTINGS_PRIVACY = 4
        const val KEY_THUMBNAIL_NOTIFICATION_PERMISSION_REQUESTED =
            "thumbnail-notification-permission-requested-v2"
    }
}
