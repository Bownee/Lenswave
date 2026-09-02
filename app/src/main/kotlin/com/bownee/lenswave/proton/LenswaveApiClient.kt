package com.bownee.lenswave.proton

import android.os.Build
import com.bownee.lenswave.BuildConfig
import me.proton.core.network.domain.ApiClient
import javax.inject.Inject

class LenswaveApiClient @Inject constructor() : ApiClient {
    override val appVersionHeader: String = "external-drive-lenswave@${BuildConfig.VERSION_NAME}-alpha"
    override val enableDebugLogging: Boolean = false
    override val userAgent: String =
        "Lenswave/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"

    override suspend fun shouldUseDoh(): Boolean = false

    override fun forceUpdate(errorMessage: String) = Unit

    override val writeTimeoutSeconds: Long = 90L
}
