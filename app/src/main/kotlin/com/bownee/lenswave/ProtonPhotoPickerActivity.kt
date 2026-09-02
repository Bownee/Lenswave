package com.bownee.lenswave

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.core.domain.entity.UserId
import com.bownee.lenswave.gallery.GalleryThumbnailLoader
import com.bownee.lenswave.gallery.ProtonThumbnailProgressCalculator
import com.bownee.lenswave.gallery.toGalleryAsset
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import com.bownee.lenswave.proton.ProtonGalleryState
import com.bownee.lenswave.proton.ProtonMetadataState
import com.bownee.lenswave.proton.ProtonThumbnailScheduler
import com.bownee.lenswave.proton.ProtonThumbnailWorkStatus
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonPhotoGateway
import com.bownee.lenswave.proton.ProtonPresentationInitializer
import javax.inject.Inject
import java.text.DateFormat
import java.util.Date

@AndroidEntryPoint
class ProtonPhotoPickerActivity : FragmentActivity() {
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var authOrchestrator: AuthOrchestrator
    @Inject lateinit var repository: ProtonPhotoGateway
    @Inject lateinit var accountSessionManager: ProtonAccountSessionManager
    @Inject lateinit var thumbnailScheduler: ProtonThumbnailScheduler

    private lateinit var screen: ProtonPhotoPickerScreen
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var grid: GridView
    private lateinit var adapter: ProtonPhotoPickerAdapter
    private lateinit var thumbnailLoader: GalleryThumbnailLoader
    private var currentUserId: UserId? = null
    private var metadataState = ProtonMetadataState()
    private var openingPhoto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        thumbnailLoader = GalleryThumbnailLoader(this, lifecycleScope, repository) { currentUserId }
        buildInterface()
        initializeAuthentication()
        observeAccount()
        observeGallery()
    }

    override fun onDestroy() {
        authOrchestrator.unregister()
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildInterface() {
        adapter = ProtonPhotoPickerAdapter()
        screen = ProtonPhotoPickerScreen(
            activity = this,
            adapter = adapter,
            actions = ProtonPhotoPickerScreen.Actions(
                close = ::finish,
                connect = {
                    status.setText(R.string.opening_proton_sign_in)
                    initializeProtonCore()
                    authOrchestrator.startLoginWorkflow()
                },
                disconnect = ::disconnect,
                retry = ::retrySync,
                openPhoto = ::openPhoto,
            ),
        )
        connectButton = screen.connectButton
        disconnectButton = screen.disconnectButton
        status = screen.status
        progress = screen.progress
        retryButton = screen.retryButton
        grid = screen.grid
        applySystemInsets(screen.root)
        setContentView(screen.root)
    }

    private fun initializeAuthentication() {
        ProtonPresentationInitializer.registerAuthentication(
            activity = this,
            accountManager = accountManager,
            authOrchestrator = authOrchestrator,
            onAuthenticationError = ::showAuthenticationError,
        )
        authOrchestrator.setOnLoginResult { result ->
            status.text = if (result == null) {
                getString(R.string.proton_sign_in_cancelled)
            } else {
                getString(R.string.completing_proton_sign_in)
            }
        }
    }

    private fun initializeProtonCore() {
        ProtonPresentationInitializer.initializeCore(applicationContext)
    }

    private fun observeAccount() {
        lifecycleScope.launch {
            accountSessionManager.state.collectLatest(::showAccount)
        }
    }

    private fun observeGallery() {
        lifecycleScope.launch {
            repository.state.collectLatest(::showGalleryState)
        }
        lifecycleScope.launch {
            repository.metadataState.collectLatest { state ->
                metadataState = state
                showGalleryState(repository.state.value)
            }
        }
        lifecycleScope.launch {
            repository.albumsState.collectLatest { showGalleryState(repository.state.value) }
        }
        lifecycleScope.launch {
            repository.trashState.collectLatest { showGalleryState(repository.state.value) }
        }
    }

    private fun showAccount(session: ProtonAccountSessionState) {
        val account = session.account
        val activeUserId = session.activeUserId
        if (activeUserId == null) {
            if (currentUserId != null) adapter.clear()
            currentUserId = null
            connectButton.visibility = if (account == null) View.VISIBLE else View.GONE
            disconnectButton.visibility = View.GONE
            grid.visibility = View.GONE
            status.text = if (account == null) {
                getString(R.string.connect_to_load_timeline)
            } else {
                getString(R.string.completing_proton_sign_in)
            }
            progress.visibility = if (account == null && !session.transitioning) View.GONE else View.VISIBLE
            return
        }

        disconnectButton.isEnabled = !session.transitioning
        if (currentUserId == activeUserId) return
        adapter.clear()
        currentUserId = activeUserId
        connectButton.visibility = View.GONE
        disconnectButton.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        lifecycleScope.launch { refreshMetadata(activeUserId, forceRemote = false) }
    }

    private fun showGalleryState(state: ProtonGalleryState) {
        if (state.userId != currentUserId?.id) return
        adapter.photos = state.photos
        progress.visibility = if (openingPhoto) View.VISIBLE else View.GONE
        val thumbnailProgress = ProtonThumbnailProgressCalculator.calculate(
            timeline = state.photos,
            albums = repository.albumsState.value.albums,
            trash = repository.trashState.value.photos,
        )
        status.text = when {
            openingPhoto -> getString(R.string.downloading_full_resolution)
            metadataState.isLoading && !metadataState.hasLoaded -> getString(R.string.loading_metadata)
            state.errorMessage != null -> getString(R.string.could_not_refresh_detail, state.errorMessage)
            state.thumbnailWorkStatus is ProtonThumbnailWorkStatus.Running &&
                thumbnailProgress.downloaded < thumbnailProgress.total -> getString(
                R.string.downloading_thumbnails_progress,
                thumbnailProgress.downloaded,
                thumbnailProgress.total,
            )
            state.photos.isEmpty() && metadataState.hasLoaded -> getString(R.string.no_photos_found)
            state.photos.isNotEmpty() -> resources.getQuantityString(
                R.plurals.photos_tap_to_edit,
                state.photos.size,
                state.photos.size,
            )
            else -> getString(R.string.loading_metadata)
        }
        retryButton.visibility = if (
            (state.errorMessage != null || metadataState.errorMessage != null) && currentUserId != null
        ) View.VISIBLE else View.GONE
    }

    private fun retrySync() {
        val userId = currentUserId ?: return
        retryButton.visibility = View.GONE
        lifecycleScope.launch { refreshMetadata(userId, forceRemote = true) }
    }

    private suspend fun refreshMetadata(userId: UserId, forceRemote: Boolean) {
        thumbnailScheduler.cancelAndAwait(userId)
        repository.syncMetadata(userId, forceRemote)
        if (repository.hasCompleteMetadata(userId)) thumbnailScheduler.enqueue(userId)
    }

    private fun openPhoto(photo: ProtonGalleryPhoto) {
        val userId = currentUserId ?: return
        if (openingPhoto) return
        if (!photo.hasThumbnail) {
            Toast.makeText(this, R.string.thumbnail_still_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        openingPhoto = true
        grid.isEnabled = false
        showGalleryState(repository.state.value)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    repository.downloadOriginal(userId, photo.nodeUid)
                }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PHOTO_PATH, file.absolutePath))
                finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                openingPhoto = false
                grid.isEnabled = true
                showGalleryState(repository.state.value)
                Toast.makeText(
                    this@ProtonPhotoPickerActivity,
                    getString(R.string.photo_download_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun disconnect() {
        val userId = currentUserId ?: return
        disconnectButton.isEnabled = false
        lifecycleScope.launch {
            accountManager.removeAccount(userId)
        }
    }

    private fun showAuthenticationError() {
        Toast.makeText(this, R.string.proton_unlock_failed, Toast.LENGTH_LONG).show()
    }

    private fun applySystemInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea: Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                dp(16) + safeArea.left,
                dp(12) + safeArea.top,
                dp(16) + safeArea.right,
                dp(12) + safeArea.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class ProtonPhotoPickerAdapter : BaseAdapter() {
        var photos: List<ProtonGalleryPhoto> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount(): Int = photos.size

        override fun getItem(position: Int): ProtonGalleryPhoto = photos[position]

        override fun getItemId(position: Int): Long = photos[position].nodeUid.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val image = (convertView as? ImageView) ?: ImageView(this@ProtonPhotoPickerActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = UiStyle.rounded(this@ProtonPhotoPickerActivity, UiStyle.surfaceRaised, 10)
                clipToOutline = true
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124))
            }
            val photo = getItem(position)
            image.tag = photo.nodeUid
            val capturedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(photo.captureTimeEpochSeconds * 1_000L))
            image.contentDescription = getString(
                R.string.proton_photo_description,
                position + 1,
                count,
                capturedAt,
            )
            ViewCompat.setStateDescription(
                image,
                if (photo.hasThumbnail) null else getString(R.string.thumbnail_unavailable),
            )
            thumbnailLoader.load(photo.toGalleryAsset()) { bitmap ->
                if (image.tag == photo.nodeUid) {
                    image.setImageBitmap(bitmap)
                    image.alpha = if (bitmap == null) 0.55f else 1f
                    ViewCompat.setStateDescription(
                        image,
                        if (bitmap == null) getString(R.string.thumbnail_unavailable) else null,
                    )
                }
            }
            return image
        }

        fun clear() {
            thumbnailLoader.clear()
            photos = emptyList()
        }
    }

    companion object {
        const val EXTRA_PHOTO_PATH = "com.bownee.lenswave.extra.PROTON_PHOTO_PATH"

    }
}
