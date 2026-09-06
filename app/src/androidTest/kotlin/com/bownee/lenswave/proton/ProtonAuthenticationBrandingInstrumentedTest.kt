package com.bownee.lenswave.proton

import android.content.Context
import android.content.Intent
import android.os.LocaleList
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.R
import me.proton.core.auth.presentation.entity.LoginInput
import me.proton.core.auth.presentation.ui.LoginActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ProtonAuthenticationBrandingInstrumentedTest {
    @Test fun actualCredentialWorkflowShowsLenswaveDisclosureAndLogoAfterRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, LoginActivity::class.java).putExtra(LoginActivity.ARG_INPUT, LoginInput(null))
        ActivityScenario.launch<LoginActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.lenswave_auth_subtitle),
                    activity.findViewById<TextView>(me.proton.core.auth.presentation.R.id.subtitleText).text.toString(),
                )
                val logo = android.util.TypedValue()
                val launcher = android.util.TypedValue()
                activity.resources.getValue(me.proton.core.auth.presentation.R.drawable.ic_logo_proton, logo, true)
                activity.resources.getValue(R.mipmap.ic_launcher, launcher, true)
                assertEquals(launcher.string.toString(), logo.string.toString())
                // The callback must override even an upstream translated subtitle.
                val configuration = android.content.res.Configuration(activity.resources.configuration)
                configuration.setLocales(LocaleList(Locale.GERMAN))
                val localized = activity.createConfigurationContext(configuration)
                val subtitle = activity.findViewById<TextView>(me.proton.core.auth.presentation.R.id.subtitleText)
                subtitle.text = localized.getString(me.proton.core.auth.presentation.R.string.auth_account_details)
                ProtonAuthenticationBranding.onActivityPostCreated(activity, null)
                assertEquals(activity.getString(R.string.lenswave_auth_subtitle), subtitle.text.toString())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.lenswave_auth_subtitle),
                    activity.findViewById<TextView>(me.proton.core.auth.presentation.R.id.subtitleText).text.toString(),
                )
            }
        }
    }
}
