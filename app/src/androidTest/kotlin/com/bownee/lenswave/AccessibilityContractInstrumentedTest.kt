package com.bownee.lenswave

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.gallery.GalleryActivity
import com.bownee.lenswave.viewer.PhotoViewerScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityContractInstrumentedTest {
    @Test fun viewerHasNamedIconActionsAndFortyEightDpTargets() {
        ActivityScenario.launch(GalleryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen =
                    PhotoViewerScreen(
                        context = activity,
                        callbacks =
                            PhotoViewerScreen.Actions(
                                gesturesEnabled = { true },
                                gestureStartAllowed = { _, _ -> true },
                                onVerticalDrag = { _, _, _ -> },
                                onHorizontalDrag = { _, _ -> },
                                onFavorite = {},
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
                listOf(screen.favoriteButton, screen.deleteButton).forEach { button ->
                    assertTrue(button.contentDescription.isNotBlank())
                    assertTrue(button.hasOnClickListeners())
                    assertTrue(button.layoutParams.width >= minimumTarget)
                    assertTrue(button.layoutParams.height >= minimumTarget)
                }
                screen.photoView.close()
            }
        }
    }
}
