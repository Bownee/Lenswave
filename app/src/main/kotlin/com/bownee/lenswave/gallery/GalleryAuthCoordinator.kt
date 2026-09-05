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
    /** Resolved on [observeAccount], not before: it stands on the session database. */
    private val accountManager: () -> AccountManager,
    private val authOrchestrator: AuthOrchestrator,
) {
    /** Registers the orchestrator with the activity; call from onCreate. */
    fun register() {
        ProtonPresentationInitializer.registerAuthentication(activity, authOrchestrator)
        authOrchestrator.setOnLoginResult { }
    }

    /** Starts routing account events into the login workflows; call once the first frame is up. */
    fun observeAccount() {
        ProtonPresentationInitializer.observeAuthentication(
            activity = activity,
            accountManager = accountManager(),
            authOrchestrator = authOrchestrator,
            onAuthenticationError = ::showAuthenticationError,
        )
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
