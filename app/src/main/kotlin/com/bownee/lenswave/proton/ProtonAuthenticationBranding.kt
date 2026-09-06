package com.bownee.lenswave.proton

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.TextView
import com.bownee.lenswave.R
import me.proton.core.auth.presentation.ui.LoginActivity
import me.proton.core.auth.presentation.ui.LoginSsoActivity

/**
 * The credential workflow skips AddAccountActivity. Apply the disclosure after Core inflates
 * the actual form; replacing only its default string would leave Core's translations in use
 * on non-English devices. The logo resource is replaced separately in proton_branding.xml.
 */
internal object ProtonAuthenticationBranding : Application.ActivityLifecycleCallbacks {
    override fun onActivityPostCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (activity is LoginActivity || activity is LoginSsoActivity) {
            activity
                .findViewById<TextView>(me.proton.core.auth.presentation.R.id.subtitleText)
                ?.setText(R.string.lenswave_auth_subtitle)
        }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
