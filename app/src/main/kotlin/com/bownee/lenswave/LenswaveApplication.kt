package com.bownee.lenswave

import android.app.Application
import com.bownee.lenswave.gallery.GalleryPreferenceWarmUp
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonCoreDatabase
import com.bownee.lenswave.proton.ProtonSessionCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager

@HiltAndroidApp
class LenswaveApplication : Application() {
    /**
     * Resolved on a background thread rather than field-injected. Building the session database
     * loads the SQLCipher library and unwraps the Keystore passphrase, and the account manager
     * pulls that database in; both are singletons that GalleryActivity's injection needs on the
     * main thread, where Dagger would otherwise build them under its DoubleCheck lock. Requesting
     * them here first means the main thread finds them built, or at worst waits for the remainder.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface StartupEntryPoint {
        fun protonCoreDatabase(): ProtonCoreDatabase

        fun accountManager(): AccountManager

        fun accountSessionManager(): ProtonAccountSessionManager

        fun sessionCache(): ProtonSessionCache
    }

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The sweep in flight, if any; read and written on the main thread, where onTrimMemory runs. */
    private var decryptedCopySweep: Job? = null

    /**
     * The UI leaving the screen is the one process-wide "app went to the background" signal
     * available without a lifecycle dependency; the plaintext copies of originals advertise a
     * 30 minute TTL, and this is where a long-lived idle process honours it. Every level from
     * UI_HIDDEN up means the app is backgrounded (BACKGROUND, MODERATE and COMPLETE too), and
     * several arrive together, so one directory walk serves them all: a new sweep starts only
     * when none is active.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN && decryptedCopySweep?.isActive != true) {
            decryptedCopySweep =
                startupScope.launch {
                    EntryPointAccessors
                        .fromApplication(this@LenswaveApplication, StartupEntryPoint::class.java)
                        .sessionCache()
                        .sweepExpiredDecryptedCopies()
                }
        }
    }

    override fun onCreate() {
        super.onCreate()
        UiStyle.initialize(this)
        startupScope.launch { GalleryPreferenceWarmUp.warm(this@LenswaveApplication) }
        val startAccountSession = accountSessionStartupEnabled
        startupScope.launch {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    this@LenswaveApplication,
                    StartupEntryPoint::class.java,
                )
            // Built for their side effects: the instances are the singletons the activity will inject.
            entryPoint.protonCoreDatabase()
            entryPoint.accountManager()
            if (startAccountSession) entryPoint.accountSessionManager().start()
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
