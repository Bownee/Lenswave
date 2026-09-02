package com.bownee.lenswave

import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bownee.lenswave.update.UpdateAvailableDialogFragment
import me.proton.core.auth.presentation.ui.LoginSsoActivity
import me.proton.core.auth.presentation.ui.LoginTwoStepActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GalleryActivityStartupTest {
    @Suppress("DEPRECATION")
    @Test
    fun protonLoginComponentsAreRegisteredAsPrivateDisabledPlaceholders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager

        listOf(LoginTwoStepActivity::class.java, LoginSsoActivity::class.java).forEach { activity ->
            val activityInfo = packageManager.getActivityInfo(
                ComponentName(context, activity),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )

            assertFalse(activityInfo.enabled)
            assertFalse(activityInfo.exported)
        }
    }

    @Test
    fun activityStartsAndSurvivesInitialCallbacks() {
        ActivityScenario.launch(GalleryActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun updateDialogSurvivesActivityRecreation() {
        ActivityScenario.launch(GalleryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                UpdateAvailableDialogFragment.create("0.20.0", "0.19.4").showNow(
                    activity.supportFragmentManager,
                    UpdateAvailableDialogFragment.TAG,
                )
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val fragments = activity.supportFragmentManager.fragments
                    .filterIsInstance<UpdateAvailableDialogFragment>()
                assertEquals(1, fragments.size)
                val dialog = fragments.single().dialog as AlertDialog
                assertTrue(dialog.isShowing)
                assertEquals(
                    activity.getString(R.string.update_available_message, "0.20.0", "0.19.4"),
                    dialog.findViewById<TextView>(android.R.id.message)?.text,
                )
            }
        }
    }
}
