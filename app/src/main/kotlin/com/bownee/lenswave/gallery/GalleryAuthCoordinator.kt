package com.bownee.lenswave.gallery

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonPresentationInitializer
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.auth.presentation.AuthOrchestrator

/** Wires Proton's auth orchestrator to the gallery activity and starts the login workflow. */
internal class GalleryAuthCoordinator(
    private val activity: FragmentActivity,
    private val accountManager: AccountManager,
    private val authOrchestrator: AuthOrchestrator,
) {
    /** Registers the orchestrator with the activity; call from onCreate. */
    fun register() {
        ProtonPresentationInitializer.registerAuthentication(
            activity = activity,
            accountManager = accountManager,
            authOrchestrator = authOrchestrator,
            onAuthenticationError = ::showAuthenticationError,
        )
        authOrchestrator.setOnLoginResult { }
    }

    /** Releases the orchestrator; call from onDestroy. */
    fun unregister() {
        authOrchestrator.unregister()
    }

    fun connectProton() {
        ProtonPresentationInitializer.initializeCore(activity.applicationContext)
        authOrchestrator.startLoginWorkflow()
    }

    private fun showAuthenticationError() {
        Toast.makeText(activity, R.string.proton_unlock_failed, Toast.LENGTH_LONG).show()
    }
}
