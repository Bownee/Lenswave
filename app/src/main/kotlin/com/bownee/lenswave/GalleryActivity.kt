package com.bownee.lenswave

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.graphics.drawable.toDrawable
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
import com.bownee.lenswave.gallery.DeviceCollection
import com.bownee.lenswave.gallery.DeviceAccessLevel
import com.bownee.lenswave.gallery.DeviceCollectionPicker
import com.bownee.lenswave.gallery.DevicePermissionPolicy
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryContent
import com.bownee.lenswave.gallery.GalleryDeletionCoordinator
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.gallery.GalleryDestinations
import com.bownee.lenswave.gallery.GalleryEmptyAction
import com.bownee.lenswave.gallery.GalleryGrouping
import com.bownee.lenswave.gallery.GalleryFastScrollLayoutPolicy
import com.bownee.lenswave.gallery.GallerySpace
import com.bownee.lenswave.gallery.GalleryThumbnailCacheIdentity
import com.bownee.lenswave.gallery.GalleryThumbnailCachePolicy
import com.bownee.lenswave.gallery.GalleryUiState
import com.bownee.lenswave.gallery.GalleryViewModel
import com.bownee.lenswave.gallery.PhotoSource
import com.bownee.lenswave.proton.ProtonAlbum
import com.bownee.lenswave.proton.ProtonPhotoGateway
import com.bownee.lenswave.gallery.PhotoDeletionExecutor
import com.bownee.lenswave.proton.ProtonPresentationInitializer
import com.bownee.lenswave.update.AppUpdateChecker
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import dagger.hilt.android.AndroidEntryPoint
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
    private val pageTitle get() = screen.pageTitle
    private val list get() = screen.list
    private val galleryFooter get() = screen.galleryFooter
    private val adapter get() = screen.adapter
    private val sourceBar get() = screen.sourceBar
    private val selectionBar get() = screen.selectionBar
    private val settingsButton get() = screen.settingsButton
    private val albumBackButton get() = screen.albumBackButton
    private val trashDeleteAllButton get() = screen.trashDeleteAllButton
    private lateinit var deletionCoordinator: GalleryDeletionCoordinator

    private val protonSourceButton get() = screen.protonSourceButton
    private val albumsSourceButton get() = screen.albumsSourceButton
    private val deviceSourceButton get() = screen.deviceSourceButton
    private val trashSourceButton get() = screen.trashSourceButton
    private val devicePicker get() = screen.devicePicker
    private val devicePickerButtons get() = screen.devicePickerButtons
    private var currentUiState = GalleryUiState()
    private var renderedDestination: GalleryDestination? = null
    private var renderedContent: GalleryContent? = null
    private var safeBottom = 0
    private var visibleAssets: List<GalleryAsset> = emptyList()
    private var fastScrollEdgePadding = 0
    private var pendingUpdateVersionName: String? = null
    private var thumbnailCacheIdentity: GalleryThumbnailCacheIdentity? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateDeviceAccess()
    }

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK ||
            result.data?.getBooleanExtra(PhotoViewerActivity.EXTRA_PHOTO_DELETED, false) != true
        ) return@registerForActivityResult
        viewModel.refreshAfterMutation()
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
        screen.retryVisibleThumbnailDownloads()
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
                onAlbumClicked = ::openAlbum,
                onSelectionChanged = ::showSelection,
                onVisibleThumbnailsChanged = viewModel::prioritizeVisibleThumbnails,
                onRefresh = {
                    hideDevicePicker()
                    viewModel.requestRefresh()
                },
                onSourceBarLayout = ::updateDevicePickerLayout,
                onAlbumBack = ::closeAlbum,
                onSpaceMenu = ::showSpaceMenu,
                onSettings = ::showSettingsMenu,
                onDeleteAllTrash = ::confirmDeleteAllTrashPhotos,
                onProtonSource = {
                    hideDevicePicker()
                    selectDestination(GalleryDestination.ProtonTimeline, scrollToTop = true)
                },
                onAlbumsSource = {
                    hideDevicePicker()
                    selectDestination(GalleryDestination.ProtonAlbums, scrollToTop = true)
                },
                onDeviceSource = {
                    if (DeviceCollectionPicker.shouldOpenMenu(currentUiState.destination)) {
                        toggleDevicePicker()
                    } else {
                        selectDeviceCollection(currentUiState.selectedDeviceCollection)
                    }
                },
                onTrashSource = {
                    hideDevicePicker()
                    val source = when (currentUiState.destination.space) {
                        GallerySpace.DEVICE -> PhotoSource.DEVICE
                        GallerySpace.COMBINED,
                        GallerySpace.PROTON -> PhotoSource.PROTON
                    }
                    selectDestination(GalleryDestination.Trash(source), scrollToTop = true)
                },
                onDeviceCollection = ::selectDeviceCollection,
                onDeleteSelection = ::deleteSelectedPhotos,
            ),
        )
        setContentView(screen.root)

        applySystemInsets()
        updatePageTitle()
        updateSourceControls()
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
        currentUiState = state
        updateThumbnailCacheIdentity(deviceAccessLevel(), state.currentUserId)
        renderedDestination = state.destination
        renderedContent = state.content
        if (contentChanged || destinationChanged) {
            when (val content = state.content) {
                is GalleryContent.Photos -> submitPhotos(content.assets)
                is GalleryContent.Albums -> {
                    visibleAssets = emptyList()
                    adapter.submitAlbums(content.albums)
                }
            }
            screen.scheduleVisibleThumbnailUpdate()
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
        updatePageTitle()
        updateSourceControls()
        if (destinationChanged) {
            adapter.clearSelection()
            hideDevicePicker()
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

    private fun selectDeviceCollection(collection: DeviceCollection) {
        hideDevicePicker()
        selectDestination(GalleryDestination.Device(collection), scrollToTop = true)
    }

    private fun selectDestination(destination: GalleryDestination, scrollToTop: Boolean = false) {
        adapter.clearSelection()
        trashDeleteAllButton.visibility = View.GONE
        viewModel.selectDestination(destination)
        if (scrollToTop) resetGalleryScroll()
    }

    private fun openAlbum(album: ProtonAlbum) {
        adapter.clearSelection()
        viewModel.openAlbum(album)
        resetGalleryScroll()
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
        resetGalleryScroll()
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
        updateNavigationVisibility(selecting)
    }

    private fun handleBack() {
        when {
            adapter.selectedPhotos().isNotEmpty() -> adapter.clearSelection()
            devicePicker.isVisible -> hideDevicePicker()
            currentUiState.destination is GalleryDestination.ProtonAlbumPhotos -> viewModel.closeAlbum()
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

    private fun updateSourceControls() {
        val destination = currentUiState.destination
        val space = destination.space
        val deviceCollection = currentUiState.selectedDeviceCollection
        val collectionLabel = getString(deviceCollection.labelRes)
        deviceSourceButton.text = getString(R.string.source_dropdown_label, collectionLabel)
        deviceSourceButton.contentDescription = getString(R.string.choose_device_collection, collectionLabel)
        protonSourceButton.visibility = if (space == GallerySpace.PROTON) View.VISIBLE else View.GONE
        albumsSourceButton.visibility = if (space == GallerySpace.PROTON) View.VISIBLE else View.GONE
        val deviceTrashAvailable = space == GallerySpace.DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        trashSourceButton.visibility = if (space == GallerySpace.PROTON || deviceTrashAvailable) {
            View.VISIBLE
        } else {
            View.GONE
        }
        deviceSourceButton.visibility = if (space == GallerySpace.DEVICE) View.VISIBLE else View.GONE
        styleSourceButton(protonSourceButton, destination == GalleryDestination.ProtonTimeline)
        styleSourceButton(
            albumsSourceButton,
            destination == GalleryDestination.ProtonAlbums ||
                destination is GalleryDestination.ProtonAlbumPhotos,
        )
        styleSourceButton(deviceSourceButton, destination is GalleryDestination.Device)
        styleSourceButton(
            trashSourceButton,
            destination is GalleryDestination.Trash,
        )
        devicePickerButtons.forEach { (collection, button) ->
            val selected = collection == deviceCollection
            val label = getString(DeviceCollectionPicker.menuLabelRes(collection))
            button.text = if (selected) getString(R.string.selected_item, label) else label
            button.isSelected = selected
            button.isActivated = selected
            ViewCompat.setStateDescription(
                button,
                getString(if (selected) R.string.selected else R.string.not_selected),
            )
            button.setTextColor(if (selected) UiStyle.text else UiStyle.muted)
            button.background = if (selected) {
                UiStyle.rounded(this, UiStyle.surfaceRaised, 14)
            } else {
                Color.TRANSPARENT.toDrawable()
            }
        }
        updateNavigationVisibility(adapter.selectedPhotos().isNotEmpty())
    }

    private fun showSpaceMenu() {
        PopupMenu(this, pageTitle).apply {
            val currentSpace = currentUiState.destination.space
            addSpaceItem(SPACE_COMBINED, GallerySpace.COMBINED, currentSpace)
            addSpaceItem(SPACE_PROTON, GallerySpace.PROTON, currentSpace)
            addSpaceItem(SPACE_DEVICE, GallerySpace.DEVICE, currentSpace)
            setOnMenuItemClickListener { item ->
                val space = when (item.itemId) {
                    SPACE_COMBINED -> GallerySpace.COMBINED
                    SPACE_PROTON -> GallerySpace.PROTON
                    SPACE_DEVICE -> GallerySpace.DEVICE
                    else -> return@setOnMenuItemClickListener false
                }
                hideDevicePicker()
                val destination = when (space) {
                    GallerySpace.DEVICE -> GalleryDestination.Device(currentUiState.selectedDeviceCollection)
                    else -> GalleryDestinations.defaultFor(space)
                }
                selectDestination(destination, scrollToTop = true)
                true
            }
            show()
        }
    }

    private fun PopupMenu.addSpaceItem(
        itemId: Int,
        space: GallerySpace,
        currentSpace: GallerySpace,
    ) {
        val spaceLabel = getString(space.labelRes)
        val title = if (space == currentSpace) getString(R.string.selected_item, spaceLabel) else spaceLabel
        menu.add(
            android.view.Menu.NONE,
            itemId,
            android.view.Menu.NONE,
            title,
        )
    }

    private fun updateNavigationVisibility(selecting: Boolean) {
        val hasContextNavigation = currentUiState.destination.space != GallerySpace.COMBINED
        sourceBar.visibility = if (!selecting && hasContextNavigation) View.VISIBLE else View.GONE
        updateGalleryFooterHeight()
    }

    private fun updateGalleryFooterHeight() {
        val height = GalleryFastScrollLayoutPolicy.footerHeight(
            navigationVisible = sourceBar.isVisible,
            bottomInset = safeBottom,
            navigationClearance = dp(78),
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

    private fun styleSourceButton(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.isActivated = selected
        ViewCompat.setStateDescription(
            button,
            getString(if (selected) R.string.selected else R.string.not_selected),
        )
        button.setTextColor(if (selected) UiStyle.text else UiStyle.muted)
        button.background = UiStyle.rounded(
            this,
            if (selected) UiStyle.surfaceRaised else Color.TRANSPARENT,
            15,
            if (selected) UiStyle.border else Color.TRANSPARENT,
        )
    }

    private fun updatePageTitle() {
        setSourceHeading(getString(currentUiState.destination.space.labelRes))
        albumBackButton.visibility = if (
            currentUiState.destination is GalleryDestination.ProtonAlbumPhotos
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setSourceHeading(label: String) {
        pageTitle.text = getString(R.string.source_dropdown_label, label)
        pageTitle.contentDescription = getString(R.string.change_photo_source, label)
    }

    private fun closeAlbum() {
        if (currentUiState.destination !is GalleryDestination.ProtonAlbumPhotos) return
        adapter.clearSelection()
        viewModel.closeAlbum()
        resetGalleryScroll()
    }

    private fun toggleDevicePicker() {
        if (devicePicker.isVisible) {
            hideDevicePicker()
        } else {
            updateSourceControls()
            updateDevicePickerLayout()
            devicePicker.visibility = View.VISIBLE
        }
    }

    private fun hideDevicePicker() {
        devicePicker.visibility = View.GONE
    }

    private fun updateDevicePickerLayout() {
        if (root.height <= 0 || deviceSourceButton.width <= 0) return
        val placement = DeviceCollectionPicker.anchoredPlacement(
            rootHeight = root.height,
            rootWidth = root.width,
            sourceBarLeft = sourceBar.left,
            sourceBarTop = sourceBar.top,
            anchorLeft = deviceSourceButton.left,
            anchorWidth = deviceSourceButton.width,
            verticalGap = dp(8),
            isRtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
        (devicePicker.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            width = placement.width
            marginStart = placement.startMargin
            marginEnd = 0
            bottomMargin = placement.bottomMargin
            devicePicker.layoutParams = this
        }
    }

    private fun resetGalleryScroll() {
        screen.resetScroll()
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
            screen.updateStickyMonthMargins(
                top = dp(8) + safeArea.top,
                start = dp(8) + if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    safeArea.right
                } else {
                    safeArea.left
                },
            )
            updateBottomOverlayInsets(sourceBar, safeArea)
            updateBottomOverlayInsets(selectionBar, safeArea)
            devicePicker.post(::updateDevicePickerLayout)
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
        const val SPACE_COMBINED = 10
        const val SPACE_PROTON = 11
        const val SPACE_DEVICE = 12
    }
}
