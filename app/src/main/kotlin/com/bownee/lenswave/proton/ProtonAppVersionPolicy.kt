package com.bownee.lenswave.proton

/**
 * Proton's app version header, built from the build's version name. Proton Core accepts only
 * `name@major.minor.patch` with at most one `-suffix`; anything else throws
 * `IllegalArgumentException("Invalid app version code")` on the first request, on a background
 * dispatcher, during sign-in. The version name may carry a pre-release tag (`1.0.0-rc1`, which
 * the root build script allows) or a variant's suffix, so only its numeric core goes into the
 * header and the app's own stage tag stays the one suffix.
 */
internal object ProtonAppVersionPolicy {
    const val APP_NAME = "external-drive-lenswave"
    const val STAGE = "alpha"

    /** Never expected: the root build script rejects a version name without a numeric core. */
    private const val FALLBACK_VERSION = "0.0.0"

    private val versionCore = Regex("""^\d+\.\d+\.\d+""")

    fun header(versionName: String): String {
        val core = versionCore.find(versionName)?.value ?: FALLBACK_VERSION
        return "$APP_NAME@$core-$STAGE"
    }
}
