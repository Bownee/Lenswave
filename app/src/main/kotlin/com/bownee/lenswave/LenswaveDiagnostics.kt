package com.bownee.lenswave

import android.util.Log
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError

/** Emits operation-level diagnostics without messages, URLs, or user/content identifiers. */
internal object LenswaveDiagnostics {
    fun reportFailure(operation: String, error: Throwable) {
        Log.w(TAG, failureSummary(operation, error))
    }

    internal fun failureSummary(operation: String, error: Throwable): String = buildString {
        append("operation=")
        append(operation)
        append(" failure=")
        append(error.diagnosticClassName())
        (error as? ProtonDriveSdkException)?.error?.let { sdkError ->
            appendSdkError("sdk", sdkError)
            sdkError.innerError?.let { innerError -> appendSdkError("inner", innerError) }
        }
    }

    private fun StringBuilder.appendSdkError(prefix: String, error: ProtonSdkError) {
        append(" ")
        append(prefix)
        append("Domain=")
        append(error.domain.name)
        error.type.safeDiagnosticValue()?.let { type ->
            append(" ")
            append(prefix)
            append("Type=")
            append(type)
        }
        error.primaryCode?.let { code ->
            append(" ")
            append(prefix)
            append("PrimaryCode=")
            append(code)
        }
        error.secondaryCode?.let { code ->
            append(" ")
            append(prefix)
            append("SecondaryCode=")
            append(code)
        }
    }

    private fun Throwable.diagnosticClassName(): String =
        if (this is ProtonDriveSdkException) "ProtonDriveSdkException" else javaClass.name

    private fun String.safeDiagnosticValue(): String? =
        takeIf { value -> SAFE_DIAGNOSTIC_VALUE.matches(value) }

    private const val TAG = "Lenswave"
    private val SAFE_DIAGNOSTIC_VALUE = Regex("[A-Za-z0-9_.-]{1,64}")
}
