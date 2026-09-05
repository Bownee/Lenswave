package com.bownee.lenswave.proton

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.startup.AppInitializer
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.accountmanager.presentation.observe
import me.proton.core.accountmanager.presentation.onAccountCreateAddressFailed
import me.proton.core.accountmanager.presentation.onAccountCreateAddressNeeded
import me.proton.core.accountmanager.presentation.onAccountDeviceSecretFailed
import me.proton.core.accountmanager.presentation.onAccountDeviceSecretNeeded
import me.proton.core.accountmanager.presentation.onAccountDisabled
import me.proton.core.accountmanager.presentation.onAccountTwoPassModeFailed
import me.proton.core.accountmanager.presentation.onAccountTwoPassModeNeeded
import me.proton.core.accountmanager.presentation.onSessionSecondFactorFailed
import me.proton.core.accountmanager.presentation.onSessionSecondFactorNeeded
import me.proton.core.accountmanager.presentation.onUserAddressKeyCheckFailed
import me.proton.core.accountmanager.presentation.onUserKeyCheckFailed
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.core.auth.presentation.MissingScopeInitializer
import me.proton.core.humanverification.presentation.HumanVerificationInitializer
import me.proton.core.network.presentation.init.UnAuthSessionFetcherInitializer

object ProtonPresentationInitializer {
    fun initializeCore(context: Context) {
        with(AppInitializer.getInstance(context.applicationContext)) {
            initializeComponent(MissingScopeInitializer::class.java)
            initializeComponent(HumanVerificationInitializer::class.java)
            initializeComponent(UnAuthSessionFetcherInitializer::class.java)
        }
    }

    fun registerAuthentication(
        activity: FragmentActivity,
        accountManager: AccountManager,
        authOrchestrator: AuthOrchestrator,
        onAuthenticationError: () -> Unit,
    ) {
        with(authOrchestrator) {
            register(activity)
            accountManager
                .observe(activity.lifecycle, minActiveState = Lifecycle.State.CREATED)
                .onSessionSecondFactorNeeded { startSecondFactorWorkflow(it) }
                .onSessionSecondFactorFailed { startLoginWorkflow(it.username) }
                .onAccountTwoPassModeNeeded { startTwoPassModeWorkflow(it) }
                .onAccountCreateAddressNeeded { startChooseAddressWorkflow(it) }
                .onAccountDeviceSecretNeeded { startDeviceSecretWorkflow(it) }
                .onAccountDeviceSecretFailed { accountManager.disableAccount(it.userId) }
                .onAccountTwoPassModeFailed { accountManager.disableAccount(it.userId) }
                .onAccountCreateAddressFailed { accountManager.disableAccount(it.userId) }
                .onAccountDisabled { accountManager.removeAccount(it.userId) }
                .onUserKeyCheckFailed { onAuthenticationError() }
                .onUserAddressKeyCheckFailed { onAuthenticationError() }
        }
    }
}
