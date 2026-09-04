package com.bownee.lenswave

import android.util.Log
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError

/** Every operation that reports diagnostics, keyed by the tag written to the log. */
internal enum class LenswaveOperation(
    val tag: String,
) {
    ACCOUNT_OBSERVER("account-observer"),
    ACCOUNT_TRANSITION("account-transition"),
    ALBUM_SYNC("album-sync"),
    ALBUM_PHOTO_SYNC("album-photo-sync"),
    APP_UPDATE_CHECK("app-update-check"),
    APP_UPDATE_EVALUATION("app-update-evaluation"),
    APP_UPDATE_SNOOZE("app-update-snooze"),
    CACHE_SNAPSHOT_READ("cache-snapshot-read"),
    DOWNLOAD_QUEUE_PERSIST("download-queue-persist"),
    ORIGINAL_CACHE_STORE("original-cache-store"),
    ORIGINAL_DOWNLOAD("original-download"),
    VIDEO_PLAYBACK("video-playback"),
    ORIGINAL_DOWNLOAD_PROGRESS("original-download-progress"),
    ORIGINAL_NAME_LOAD("original-name-load"),
    PROTON_CLIENT_CREATE("proton-client-create"),
    SESSION_DATABASE_REKEY("session-database-rekey"),
    SESSION_HOUSEKEEPING("session-housekeeping"),
    THUMBNAIL_DOWNLOAD("thumbnail-download"),
    PREVIEW_DOWNLOAD("preview-download"),
    THUMBNAIL_WORKER("thumbnail-worker"),
    TIMELINE_SYNC("timeline-sync"),
}

/** Emits operation-level diagnostics without messages, URLs, or user/content identifiers. */
internal object LenswaveDiagnostics {
    fun reportFailure(
        operation: LenswaveOperation,
        error: Throwable,
    ) {
        reportFailure(operation.tag, error)
    }

    fun reportFailure(
        operation: String,
        error: Throwable,
    ) {
        Log.w(TAG, failureSummary(operation, error))
    }

    fun reportState(
        operation: LenswaveOperation,
        state: String,
        attempt: Int,
        maximumAttempts: Int,
    ) {
        reportState(operation.tag, state, attempt, maximumAttempts)
    }

    fun reportState(
        operation: String,
        state: String,
        attempt: Int,
        maximumAttempts: Int,
    ) {
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

    internal fun failureSummary(
        operation: String,
        error: Throwable,
    ): String =
        buildString {
            append("operation=")
            append(operation)
            append(" failure=")
            append(error.diagnosticClassName())
            (error as? ProtonDriveSdkException)?.let { sdkException ->
                val sdkError = sdkException.error
                if (sdkError == null) {
                    // Some SDK failures carry no structured error; the message is the only clue.
                    sdkException.message?.let { message ->
                        append(" sdkMessage=")
                        append(message.sanitizedDiagnosticText())
                    }
                    sdkException.cause?.let { cause ->
                        append(" sdkCause=")
                        append(cause.javaClass.name)
                    }
                    return@let
                }
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

    /** Keeps letters, digits and basic punctuation so a free-form message cannot smuggle secrets or newlines. */
    private fun String.sanitizedDiagnosticText(): String =
        replace(Regex("[^A-Za-z0-9 _.:,()/-]"), "_").take(MAX_MESSAGE_LENGTH)

    private fun StringBuilder.appendSdkError(
        prefix: String,
        error: ProtonSdkError,
    ) {
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

    private fun String.safeDiagnosticValue(): String? = takeIf { value -> SAFE_DIAGNOSTIC_VALUE.matches(value) }

    private fun ProtonSdkError.safeStackFrames(): List<String> =
        context
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .mapNotNull { line -> SAFE_STACK_FRAME.matchEntire(line)?.groupValues?.get(1) }
            .take(MAX_STACK_FRAMES)
            .toList()

    private const val TAG = "Lenswave"
    private const val MAX_STACK_FRAMES = 4
    private const val MAX_MESSAGE_LENGTH = 160
    private val SAFE_DIAGNOSTIC_VALUE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val SAFE_STACK_FRAME =
        Regex(
            """at ([A-Za-z0-9_.${'$'}<>-]+\.[A-Za-z0-9_${'$'}<>-]+\([A-Za-z0-9_.: -]{1,100}\))""",
        )
}
