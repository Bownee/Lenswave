package com.bownee.lenswave

import android.app.Application
import com.bownee.lenswave.gallery.GalleryPreferenceWarmUp
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LenswaveApplication : Application() {
    /**
     * Resolved on a background thread rather than field-injected: building the account session
     * manager pulls in the account database (SQLCipher library load, Keystore passphrase unwrap),
     * which must not run on the main thread during onCreate.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface StartupEntryPoint {
        fun accountSessionManager(): ProtonAccountSessionManager
    }

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        UiStyle.initialize(this)
        startupScope.launch { GalleryPreferenceWarmUp.warm(this@LenswaveApplication) }
        if (accountSessionStartupEnabled) {
            startupScope.launch {
                EntryPointAccessors
                    .fromApplication(this@LenswaveApplication, StartupEntryPoint::class.java)
                    .accountSessionManager()
                    .start()
            }
        }
    }

    companion object {
        @Volatile private var accountSessionStartupEnabled = true

        @Volatile private var appUpdateStartupEnabled = true

        internal fun isAppUpdateStartupEnabled(): Boolean = appUpdateStartupEnabled

        internal fun disableAccountSessionStartupForTests() {
            accountSessionStartupEnabled = false
        }

        internal fun disableAppUpdateStartupForTests() {
            appUpdateStartupEnabled = false
        }
    }
}
