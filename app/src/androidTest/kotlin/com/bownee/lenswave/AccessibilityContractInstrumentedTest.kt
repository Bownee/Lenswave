package com.bownee.lenswave

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityContractInstrumentedTest {
    @Test fun viewerHasNamedIconActionsAndFortyEightDpTargets() {
        ActivityScenario.launch(GalleryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = PhotoViewerScreen(
                    context = activity,
                    requestIsTrashed = false,
                    actions = PhotoViewerScreen.Actions(
                        gesturesEnabled = { true },
                        onVerticalDrag = { _, _, _ -> },
                        onHorizontalDrag = { _, _ -> },
                        onBack = {},
                        onEdit = {},
                        onDelete = {},
                        onRetry = {},
                        onLayoutChanged = {},
                    ),
                )
                screen.root.measure(
                    View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
                )
                screen.root.layout(0, 0, 1_080, 1_920)
                val minimumTarget = activity.dp(48)
                listOf(
                    screen.editButton,
                    screen.deleteButton,
                ).forEach { button ->
                    assertTrue(button.contentDescription.isNotBlank())
                    assertTrue(button.hasOnClickListeners())
                    assertTrue(button.measuredWidth >= minimumTarget)
                    assertTrue(button.measuredHeight >= minimumTarget)
                }
                screen.photoView.close()
            }
        }
    }

    @Test fun editorSliderAndOriginalToggleExposeSemanticActions() {
        ActivityScenario.launch(PhotoEditorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val slider = activity.window.decorView.firstDescendant<SeekBar>()
                assertNotNull(slider.contentDescription)
                assertTrue(slider.contentDescription.toString().isNotBlank())
                assertNotNull(ViewCompat.getStateDescription(slider))
                assertTrue(slider.minimumHeight >= activity.dp(48))

                val original = activity.window.decorView.descendants()
                    .filterIsInstance<ImageButton>()
                    .first { it.contentDescription == activity.getString(R.string.show_original_toggle) }
                assertTrue(original.hasOnClickListeners())
                assertEquals(activity.dp(48), original.layoutParams.height)
            }
        }
    }

    private inline fun <reified T : View> View.firstDescendant(): T =
        descendants().filterIsInstance<T>().first()

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }
}
