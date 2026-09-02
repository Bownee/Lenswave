package com.bownee.lenswave

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.bownee.lenswave.storage.TransientPhotoFiles
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

class PhotoEditorActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val selectionGeneration = AtomicLong()
    private val undoHistory = ArrayDeque<EditorState>()

    private lateinit var adjustmentNames: Array<String>
    private lateinit var lookNames: Array<String>
    private lateinit var photoPicker: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var protonPhotoPicker: ActivityResultLauncher<Intent>
    private lateinit var screen: PhotoEditorScreen

    private var editorState = EditorState.INITIAL
    private var dragStart: EditorState? = null
    private var previewBitmap: Bitmap? = null
    private var selectedPhoto: Uri? = null
    private var selectedAdjustment = PhotoAdjustments.BRIGHTNESS
    private var selectedSection = SECTION_ADJUST
    private var suppressSliderEvents = false
    private var busy = false
    private var restoringState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adjustmentNames = resources.getStringArray(R.array.adjustment_names)
        lookNames = resources.getStringArray(R.array.look_names)
        configureWindow()
        photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia(), ::onPhotoSelected)
        protonPhotoPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val path = result.data?.getStringExtra(ProtonPhotoPickerActivity.EXTRA_PHOTO_PATH) ?: return@registerForActivityResult
            loadPhoto(Uri.fromFile(File(path)))
        }

        savedInstanceState?.let(::restoreEditorState)
        buildInterface()
        showSection(selectedSection)

        val initialPhoto = if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_PHOTO_URI)
        } else {
            selectedPhoto?.toString()
        }
        if (initialPhoto != null) {
            restoringState = savedInstanceState != null
            loadPhoto(initialPhoto.toUri())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedPhoto?.let { outState.putString(STATE_PHOTO_URI, it.toString()) }
        outState.putBundle(STATE_EDITOR, editorStateToBundle(editorState))
        outState.putInt(STATE_ADJUSTMENT, selectedAdjustment)
        outState.putInt(STATE_SECTION, selectedSection)
        outState.putParcelableArrayList(
            STATE_UNDO,
            ArrayList<Bundle>().apply { undoHistory.forEach { add(editorStateToBundle(it)) } },
        )
    }

    override fun onResume() {
        super.onResume()
        screen.photoView.onResume()
    }

    override fun onPause() {
        screen.photoView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        selectionGeneration.incrementAndGet()
        worker.shutdownNow()
        // The GL renderer owns the current bitmap until its rendering thread is released.
        previewBitmap = null
        if (isFinishing) TransientPhotoFiles.deleteIfOwned(this, selectedPhoto)
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
        screen = PhotoEditorScreen(this, selectedAdjustment, object : PhotoEditorScreen.Actions {
            override fun choosePhoto() = this@PhotoEditorActivity.choosePhoto()

            override fun showSection(section: Int) = this@PhotoEditorActivity.showSection(section)

            override fun selectAdjustment(adjustment: Int) = this@PhotoEditorActivity.selectAdjustment(adjustment)

            override fun changeAdjustment(progress: Int) = this@PhotoEditorActivity.changeAdjustment(progress)

            override fun startAdjustment() {
                dragStart = editorState
            }

            override fun finishAdjustment() {
                dragStart?.takeIf { it != editorState }?.let(undoHistory::push)
                dragStart = null
                refreshEditorUi()
            }

            override fun applyLook(look: Int) = this@PhotoEditorActivity.applyLook(look)

            override fun rotate() = changeState(editorState.withAdjustments(editorState.adjustments.rotateClockwise()))

            override fun undo() = this@PhotoEditorActivity.undo()

            override fun reset() = changeState(EditorState.INITIAL)

            override fun canShowOriginal(): Boolean = canEdit()

            override fun showOriginal(show: Boolean) {
                screen.photoView.setAdjustments(if (show) PhotoAdjustments.NEUTRAL else editorState.adjustments)
            }

            override fun save() = saveCopy()
        })

        ViewCompat.setAccessibilityDelegate(screen.adjustmentSlider, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val value = (editorState.adjustments.value(selectedAdjustment) * 100f).roundToInt().toFloat()
                val maximum = adjustmentMaximum(selectedAdjustment)
                val minimum = if (selectedAdjustment == PhotoAdjustments.VIGNETTE) 0f else -maximum
                info.contentDescription = adjustmentNames[selectedAdjustment]
                info.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                    AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT,
                    minimum,
                    maximum,
                    value,
                )
            }
        })
        applySystemInsets(screen.root)
        setContentView(screen.root)
        showSection(SECTION_ADJUST)
        refreshEditorUi()
    }

    private fun changeAdjustment(progress: Int) {
        if (suppressSliderEvents) return
        val value = sliderToValue(selectedAdjustment, progress)
        val previousValue = editorState.adjustments.value(selectedAdjustment)
        val updated = editorState.adjustments.withValue(selectedAdjustment, value)
        editorState = editorState.withAdjustments(updated)
        screen.photoView.setAdjustments(updated)
        updateAdjustmentValue()
        if ((abs(previousValue) > ACTIVE_THRESHOLD) != (abs(value) > ACTIVE_THRESHOLD)) {
            refreshAdjustmentButtons()
        }
    }

    private fun applySystemInsets(root: View) {
        val horizontalPadding = dp(12)
        val topPadding = dp(8)
        val bottomPadding = dp(10)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safeArea: Insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                horizontalPadding + safeArea.left,
                topPadding + safeArea.top,
                horizontalPadding + safeArea.right,
                bottomPadding + safeArea.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun choosePhoto() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_photo)
            .setItems(arrayOf(getString(R.string.space_device), getString(R.string.space_proton))) { _, which ->
                if (which == 0) launchDevicePicker() else {
                    protonPhotoPicker.launch(Intent(this, ProtonPhotoPickerActivity::class.java))
                }
            }
            .show()
    }

    private fun launchDevicePicker() {
        photoPicker.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build(),
        )
    }

    private fun onPhotoSelected(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Framework photo-picker grants are intentionally session-scoped.
        } catch (_: IllegalArgumentException) {
            // Framework photo-picker grants are intentionally session-scoped.
        }
        loadPhoto(uri)
    }

    private fun loadPhoto(uri: Uri) {
        val generation = selectionGeneration.incrementAndGet()
        setBusy(true)
        worker.execute {
            try {
                val loaded = ImageProcessor.decodePreview(this, uri)
                runOnUiThread {
                    if (generation != selectionGeneration.get()) {
                        loaded.recycle()
                        return@runOnUiThread
                    }
                    val previous = previewBitmap
                    previewBitmap = loaded
                    val previousPhoto = selectedPhoto
                    selectedPhoto = uri
                    previousPhoto?.takeIf { it != uri }?.let { TransientPhotoFiles.deleteIfOwned(this, it) }
                    if (!restoringState) {
                        editorState = EditorState.INITIAL
                        undoHistory.clear()
                    }
                    screen.photoView.setBitmap(loaded) {
                        previous?.takeIf { it !== loaded && !it.isRecycled }?.recycle()
                    }
                    screen.photoView.setAdjustments(editorState.adjustments)
                    screen.emptyState.visibility = View.GONE
                    showSection(if (restoringState) selectedSection else SECTION_ADJUST)
                    selectAdjustment(if (restoringState) selectedAdjustment else PhotoAdjustments.BRIGHTNESS)
                    restoringState = false
                    setBusy(false)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    if (generation != selectionGeneration.get()) return@runOnUiThread
                    setBusy(false)
                    showToast(getString(R.string.could_not_open_photo), exception)
                }
            }
        }
    }

    private fun saveCopy() {
        val source = selectedPhoto ?: return
        if (busy) return
        val exportAdjustments = editorState.adjustments
        val generation = selectionGeneration.get()
        setBusy(true)
        Toast.makeText(this, R.string.rendering_full_size, Toast.LENGTH_SHORT).show()
        worker.execute {
            var exportBitmap: Bitmap? = null
            try {
                exportBitmap = ImageProcessor.renderFullResolution(this, source, exportAdjustments)
                val saved = ImageProcessor.saveJpegCopy(this, exportBitmap)
                runOnUiThread {
                    if (generation != selectionGeneration.get() || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    Toast.makeText(this, R.string.saved_to_lenswave, Toast.LENGTH_LONG).show()
                    offerShare(saved)
                }
            } catch (_: CancellationException) {
                Thread.currentThread().interrupt()
            } catch (_: OutOfMemoryError) {
                showWorkerFailure(generation, getString(R.string.photo_too_large))
            } catch (_: Exception) {
                showWorkerFailure(generation, getString(R.string.could_not_save_copy))
            } finally {
                exportBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    private fun offerShare(savedPhoto: Uri) {
        screen.saveButton.setOnLongClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, savedPhoto)
                clipData = ClipData(
                    ClipDescription(getString(R.string.edited_photo), arrayOf("image/jpeg")),
                    ClipData.Item(savedPhoto),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_edited_photo)))
            true
        }
    }

    private fun applyLook(look: Int) {
        if (!canEdit()) return
        val rotation = editorState.adjustments.rotationQuarterTurns
        val next = when (look) {
            0 -> PhotoAdjustments(0.04f, 0.12f, -0.04f, 0.16f, 0.10f, 0.01f, 0f, 0f, rotation)
            1 -> PhotoAdjustments(0.01f, 0.20f, -0.06f, 0.08f, 0.32f, 0.02f, 0f, 0.08f, rotation)
            2 -> PhotoAdjustments(0.03f, 0.08f, 0f, 0.10f, 0.12f, 0.30f, 0.04f, 0.05f, rotation)
            3 -> PhotoAdjustments(0.02f, 0.22f, -0.05f, 0.10f, -1f, 0f, 0f, 0.10f, rotation)
            else -> editorState.adjustments
        }
        changeState(EditorState(next, look))
    }

    private fun selectAdjustment(adjustment: Int) {
        selectedAdjustment = adjustment
        screen.adjustmentLabel.text = adjustmentNames[adjustment]
        updateSliderFromState()
        refreshAdjustmentButtons()
    }

    private fun showSection(section: Int) {
        selectedSection = section
        screen.adjustPanel.visibility = if (section == SECTION_ADJUST) View.VISIBLE else View.GONE
        screen.looksPanel.visibility = if (section == SECTION_LOOKS) View.VISIBLE else View.GONE
        screen.updateTabAppearance(screen.adjustTab, section == SECTION_ADJUST)
        screen.updateTabAppearance(screen.looksTab, section == SECTION_LOOKS)
    }

    private fun changeState(next: EditorState) {
        if (!canEdit() || editorState == next) return
        undoHistory.push(editorState)
        editorState = next
        refreshEditorUi()
    }

    private fun undo() {
        if (!canEdit() || undoHistory.isEmpty()) return
        editorState = undoHistory.pop()
        refreshEditorUi()
    }

    private fun refreshEditorUi() {
        if (::screen.isInitialized) {
            screen.photoView.setAdjustments(editorState.adjustments)
            val activeAdjustmentCount = (PhotoAdjustments.BRIGHTNESS..PhotoAdjustments.VIGNETTE)
                .count { abs(editorState.adjustments.value(it)) > ACTIVE_THRESHOLD }
            val adjustmentSummary = resources.getQuantityString(
                R.plurals.active_adjustment_count,
                activeAdjustmentCount,
                activeAdjustmentCount,
            )
            screen.photoView.contentDescription = getString(
                R.string.editor_image_state,
                editorState.adjustments.rotationQuarterTurns * 90,
                adjustmentSummary,
            )
            screen.setOriginalShown(false)
            updateSliderFromState()
        }
        refreshAdjustmentButtons()
        refreshLookIndicators()
        updateEnabledState()
    }

    private fun refreshAdjustmentButtons() {
        screen.adjustmentButtons.forEachIndexed { index, button ->
            if (button == null) return@forEachIndexed
            val selected = index == selectedAdjustment
            val active = abs(editorState.adjustments.value(index)) > ACTIVE_THRESHOLD
            button.isSelected = selected
            button.isActivated = active
            ViewCompat.setStateDescription(
                button,
                getString(
                    when {
                        selected && active -> R.string.selected_adjustment_active
                        selected -> R.string.selected
                        active -> R.string.adjustment_active
                        else -> R.string.not_selected
                    },
                ),
            )
            button.text = if (active) getString(R.string.active_adjustment, adjustmentNames[index]) else adjustmentNames[index]
            screen.applyButtonAppearance(
                button,
                if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE_RAISED,
                if (selected || active) COLOR_ACCENT else COLOR_TEXT,
                if (selected || active) COLOR_ACCENT else COLOR_BORDER,
                dp(14),
            )
        }
    }

    private fun refreshLookIndicators() {
        val activeLook = editorState.activeLook
        screen.lookButtons.forEachIndexed { index, button ->
            if (button == null) return@forEachIndexed
            val active = index == activeLook
            button.isSelected = active
            ViewCompat.setStateDescription(button, getString(if (active) R.string.selected else R.string.not_selected))
            button.text = if (active) getString(R.string.selected_look, lookNames[index]) else lookNames[index]
            screen.applyButtonAppearance(
                button,
                if (active) COLOR_ACCENT else COLOR_SURFACE_RAISED,
                if (active) Color.rgb(24, 20, 41) else COLOR_TEXT,
                if (active) COLOR_ACCENT else COLOR_BORDER,
                dp(14),
            )
        }

        if (activeLook in lookNames.indices) {
            screen.lookStatus.text = getString(R.string.active_look_description, lookNames[activeLook])
            screen.lookStatus.setTextColor(COLOR_ACCENT)
        } else {
            screen.lookStatus.setText(R.string.choose_look_prompt)
            screen.lookStatus.setTextColor(COLOR_MUTED)
        }
    }

    private fun updateSliderFromState() {
        suppressSliderEvents = true
        screen.adjustmentSlider.progress = valueToSlider(
            selectedAdjustment,
            editorState.adjustments.value(selectedAdjustment),
        )
        suppressSliderEvents = false
        updateAdjustmentValue()
    }

    private fun updateAdjustmentValue() {
        val value = editorState.adjustments.value(selectedAdjustment)
        val displayValue = String.format(Locale.getDefault(), "%+d", (value * 100f).roundToInt())
        screen.adjustmentValue.text = displayValue
        screen.adjustmentSlider.contentDescription = adjustmentNames[selectedAdjustment]
        ViewCompat.setStateDescription(
            screen.adjustmentSlider,
            getString(R.string.adjustment_state, adjustmentNames[selectedAdjustment], displayValue),
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        screen.progressBar.visibility = if (value) View.VISIBLE else View.INVISIBLE
        updateEnabledState()
    }

    private fun updateEnabledState() {
        val canEdit = canEdit()
        setEnabled(screen.saveButton, canEdit)
        setEnabled(screen.photoButton, !busy)
        setEnabled(screen.emptyPhotoButton, !busy)
        setEnabled(screen.adjustTab, canEdit)
        setEnabled(screen.looksTab, canEdit)
        setEnabled(screen.adjustmentSlider, canEdit)
        setEnabled(screen.rotateButton, canEdit)
        setEnabled(screen.resetButton, canEdit)
        setEnabled(screen.beforeButton, canEdit)
        setEnabled(screen.undoButton, canEdit && undoHistory.isNotEmpty())
        screen.adjustmentButtons.forEach { setEnabled(it, canEdit) }
        screen.lookButtons.forEach { setEnabled(it, canEdit) }
    }

    private fun canEdit(): Boolean = selectedPhoto != null && !busy

    private fun showWorkerFailure(generation: Long, title: String) {
        runOnUiThread {
            if (generation != selectionGeneration.get() || isDestroyed) return@runOnUiThread
            setBusy(false)
            Toast.makeText(this, title, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showToast(title: String, throwable: Throwable) {
        Toast.makeText(this, title, Toast.LENGTH_LONG).show()
    }

    private fun setEnabled(view: View?, enabled: Boolean) {
        view ?: return
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.42f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun restoreEditorState(savedState: Bundle) {
        selectedPhoto = savedState.getString(STATE_PHOTO_URI)?.toUri()
        editorState = editorStateFromBundle(savedState.getBundle(STATE_EDITOR))
        selectedAdjustment = savedState.getInt(STATE_ADJUSTMENT, PhotoAdjustments.BRIGHTNESS)
        selectedSection = savedState.getInt(STATE_SECTION, SECTION_ADJUST)
        undoHistory.clear()
        parcelableBundleList(savedState, STATE_UNDO)?.forEach {
            undoHistory.addLast(editorStateFromBundle(it))
        }
    }

    companion object {
        const val EXTRA_PHOTO_URI = "com.bownee.lenswave.extra.EDITOR_PHOTO_URI"

        private val COLOR_SURFACE_RAISED = PhotoEditorScreen.COLOR_SURFACE_RAISED
        private val COLOR_BORDER = PhotoEditorScreen.COLOR_BORDER
        private val COLOR_TEXT = PhotoEditorScreen.COLOR_TEXT
        private val COLOR_MUTED = PhotoEditorScreen.COLOR_MUTED
        private val COLOR_ACCENT = PhotoEditorScreen.COLOR_ACCENT
        private val COLOR_ACCENT_DARK = PhotoEditorScreen.COLOR_ACCENT_DARK
        private const val SECTION_ADJUST = 0
        private const val SECTION_LOOKS = 1
        private const val ACTIVE_THRESHOLD = 0.0001f

        private const val STATE_PHOTO_URI = "editor.photo-uri"
        private const val STATE_EDITOR = "editor.state"
        private const val STATE_ADJUSTMENT = "editor.adjustment"
        private const val STATE_SECTION = "editor.section"
        private const val STATE_UNDO = "editor.undo"
        private const val STATE_VALUES = "editor.values"
        private const val STATE_ROTATION = "editor.rotation"
        private const val STATE_LOOK = "editor.look"

        @Suppress("DEPRECATION")
        private fun parcelableBundleList(state: Bundle, key: String): ArrayList<Bundle>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                state.getParcelableArrayList(key, Bundle::class.java)
            } else {
                state.getParcelableArrayList(key)
            }

        private fun editorStateToBundle(state: EditorState) = Bundle().apply {
            val adjustments = state.adjustments
            putFloatArray(
                STATE_VALUES,
                floatArrayOf(
                    adjustments.brightness,
                    adjustments.contrast,
                    adjustments.highlights,
                    adjustments.shadows,
                    adjustments.saturation,
                    adjustments.warmth,
                    adjustments.tint,
                    adjustments.vignette,
                ),
            )
            putInt(STATE_ROTATION, adjustments.rotationQuarterTurns)
            putInt(STATE_LOOK, state.activeLook)
        }

        private fun editorStateFromBundle(value: Bundle?): EditorState {
            if (value == null) return EditorState.INITIAL
            val values = value.getFloatArray(STATE_VALUES)?.takeIf { it.size == 8 } ?: return EditorState.INITIAL
            return EditorState(
                PhotoAdjustments(
                    values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7],
                    value.getInt(STATE_ROTATION),
                ),
                value.getInt(STATE_LOOK, EditorState.NO_LOOK),
            )
        }

        private fun sliderToValue(adjustment: Int, progress: Int): Float {
            if (adjustment == PhotoAdjustments.VIGNETTE) return progress / 200f
            val normalized = (progress - 100) / 100f
            return when (adjustment) {
                PhotoAdjustments.BRIGHTNESS -> normalized * 0.5f
                PhotoAdjustments.CONTRAST,
                PhotoAdjustments.HIGHLIGHTS,
                PhotoAdjustments.SHADOWS,
                -> normalized * 0.8f
                else -> normalized
            }
        }

        private fun valueToSlider(adjustment: Int, value: Float): Int {
            if (adjustment == PhotoAdjustments.VIGNETTE) return (value * 200f).roundToInt()
            val scale = when (adjustment) {
                PhotoAdjustments.BRIGHTNESS -> 0.5f
                PhotoAdjustments.CONTRAST,
                PhotoAdjustments.HIGHLIGHTS,
                PhotoAdjustments.SHADOWS,
                -> 0.8f
                else -> 1f
            }
            return (value / scale * 100f + 100f).roundToInt()
        }

        private fun adjustmentMaximum(adjustment: Int): Float = when (adjustment) {
            PhotoAdjustments.BRIGHTNESS -> 50f
            PhotoAdjustments.CONTRAST,
            PhotoAdjustments.HIGHLIGHTS,
            PhotoAdjustments.SHADOWS,
            -> 80f
            else -> 100f
        }
    }
}
