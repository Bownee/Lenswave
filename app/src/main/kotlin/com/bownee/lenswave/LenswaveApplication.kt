package com.bownee.lenswave

import android.app.Application
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LenswaveApplication : Application() {
    @Inject lateinit var protonAccountSessionManager: ProtonAccountSessionManager

    override fun onCreate() {
        super.onCreate()
        UiStyle.initialize(this)
        if (accountSessionStartupEnabled) protonAccountSessionManager.start()
    }

    companion object {
        @Volatile private var accountSessionStartupEnabled = true

        internal fun disableAccountSessionStartupForTests() {
            accountSessionStartupEnabled = false
        }
    }
}
