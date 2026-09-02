package com.bownee.lenswave

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoEditorStateInstrumentedTest {
    @Test
    fun ownedTransientPhotoSurvivesRecreationAndIsDeletedOnFinish() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "proton-decrypted/editor-state-test").apply { mkdirs() }
        val photo = File(directory, "photo.png")
        writePhoto(photo)
        val intent = Intent(context, PhotoEditorActivity::class.java)
            .putExtra(PhotoEditorActivity.EXTRA_PHOTO_URI, Uri.fromFile(photo).toString())

        ActivityScenario.launch<PhotoEditorActivity>(intent).use { scenario ->
            awaitEnabledSlider(scenario)
            scenario.recreate()
            awaitEnabledSlider(scenario)
            assertTrue(photo.isFile)
        }

        assertFalse(photo.exists())
    }

    @Test
    fun adjustmentAndPhotoSurviveActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = File(context.cacheDir, "editor-state-test.png")
        writePhoto(photo)

        val intent = Intent(context, PhotoEditorActivity::class.java)
            .putExtra(PhotoEditorActivity.EXTRA_PHOTO_URI, Uri.fromFile(photo).toString())
        try {
            ActivityScenario.launch<PhotoEditorActivity>(intent).use { scenario ->
                var slider = awaitEnabledSlider(scenario)
                val changedProgress = AtomicInteger()
                scenario.onActivity { activity ->
                    val value = requireNotNull(find(activity.window.decorView, SeekBar::class.java))
                    val time = SystemClock.uptimeMillis()
                    val y = value.height / 2f
                    dispatch(value, time, MotionEvent.ACTION_DOWN, value.width / 2f, y)
                    dispatch(value, time + 10, MotionEvent.ACTION_MOVE, value.width * 0.8f, y)
                    dispatch(value, time + 20, MotionEvent.ACTION_UP, value.width * 0.8f, y)
                    changedProgress.set(value.progress)
                }
                assertTrue(changedProgress.get() > 100)
                scenario.recreate()
                slider = awaitEnabledSlider(scenario)
                assertEquals(changedProgress.get(), slider.progress)
            }
        } finally {
            photo.delete()
        }
    }

    private fun writePhoto(photo: File) {
        val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(0xff6688aa.toInt())
            FileOutputStream(photo).use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            source.recycle()
        }
    }

    private fun dispatch(view: View, time: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(time, time, action, x, y, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun awaitEnabledSlider(scenario: ActivityScenario<PhotoEditorActivity>): SeekBar {
        val result = AtomicReference<SeekBar>()
        val deadline = SystemClock.uptimeMillis() + EDITOR_LOAD_TIMEOUT_MILLIS
        do {
            scenario.onActivity { activity ->
                find(activity.window.decorView, SeekBar::class.java)?.takeIf(View::isEnabled)?.let(result::set)
            }
            result.get()?.let { return it }
            Thread.sleep(EDITOR_LOAD_POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Editor did not finish loading the test photo")
    }

    private fun <T : View> find(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) find(root.getChildAt(index), type)?.let { return it }
        }
        return null
    }

    private companion object {
        const val EDITOR_LOAD_TIMEOUT_MILLIS = 20_000L
        const val EDITOR_LOAD_POLL_INTERVAL_MILLIS = 50L
    }
}
