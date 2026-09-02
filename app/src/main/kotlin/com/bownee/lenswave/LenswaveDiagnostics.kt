package com.bownee.lenswave

import android.util.Log
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError

/** Emits operation-level diagnostics without messages, URLs, or user/content identifiers. */
internal object LenswaveDiagnostics {
    fun reportFailure(operation: String, error: Throwable) {
        Log.w(TAG, failureSummary(operation, error))
    }

    fun reportState(operation: String, state: String, attempt: Int, maximumAttempts: Int) {
        Log.i(TAG, stateSummary(operation, state, attempt, maximumAttempts))
    }

    internal fun stateSummary(
        operation: String,
        state: String,
        attempt: Int,
        maximumAttempts: Int,
    ): String {
        require(SAFE_DIAGNOSTIC_VALUE.matches(operation))
        require(SAFE_DIAGNOSTIC_VALUE.matches(state))
        require(attempt in 1..maximumAttempts)
        return "operation=$operation state=$state attempt=$attempt maximumAttempts=$maximumAttempts"
    }

    internal fun failureSummary(operation: String, error: Throwable): String = buildString {
        append("operation=")
        append(operation)
        append(" failure=")
        append(error.diagnosticClassName())
        (error as? ProtonDriveSdkException)?.error?.let { sdkError ->
            appendSdkError("sdk", sdkError)
            sdkError.innerError?.let { innerError -> appendSdkError("inner", innerError) }
            sdkError.safeStackFrames().forEachIndexed { index, frame ->
                append(" sdkFrame")
                append(index + 1)
                append("=")
                append(frame)
            }
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

    private fun ProtonSdkError.safeStackFrames(): List<String> = context
        .orEmpty()
        .lineSequence()
        .map(String::trim)
        .mapNotNull { line -> SAFE_STACK_FRAME.matchEntire(line)?.groupValues?.get(1) }
        .take(MAX_STACK_FRAMES)
        .toList()

    private const val TAG = "Lenswave"
    private const val MAX_STACK_FRAMES = 4
    private val SAFE_DIAGNOSTIC_VALUE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val SAFE_STACK_FRAME = Regex(
        """at ([A-Za-z0-9_.${'$'}<>-]+\.[A-Za-z0-9_${'$'}<>-]+\([A-Za-z0-9_.: -]{1,100}\))""",
    )
}
