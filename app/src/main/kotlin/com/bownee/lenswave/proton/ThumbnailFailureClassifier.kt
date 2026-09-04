package com.bownee.lenswave.proton

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError

/**
 * Only three failures change what happens next: a missing rendition gets the thumbnail as its
 * preview, an unanswered node is asked again on its own, and everything else backs off.
 */
internal enum class ThumbnailFailureKind(
    val priority: Int,
) {
    OTHER(0),

    /** The SDK gave no answer for the node before the pass ended; asked again on its own first. */
    UNANSWERED(1),

    /** Proton has no such rendition for the photo; retrying cannot help. */
    NOT_FOUND(2),
}

/**
 * What a node is left with after the preview rendition was tried in place of its thumbnail.
 * A missing preview says nothing about the thumbnail: a node whose thumbnail pass went
 * unanswered keeps that status and is retried, instead of being dropped as if Proton had
 * refused the thumbnail itself.
 */
internal object ThumbnailFallbackFailurePolicy {
    fun settle(
        thumbnailKind: ThumbnailFailureKind?,
        previewKind: ThumbnailFailureKind,
    ): ThumbnailFailureKind {
        val thumbnail = thumbnailKind ?: ThumbnailFailureKind.OTHER
        if (previewKind == ThumbnailFailureKind.NOT_FOUND) return thumbnail
        return if (previewKind.priority > thumbnail.priority) previewKind else thumbnail
    }

    /**
     * A node whose fallback pass never started (previews were not allowed at the time) is not
     * a failure of any kind: the thumbnail failure that sent it to the fallback must not be
     * settled either, or a "no thumbnail" answer would drop it before it ever got its preview.
     * Such nodes stay claimed and untouched until a run that may fetch previews reaches them.
     */
    fun withoutDeferred(
        failures: Map<String, ThumbnailFailureKind>,
        deferredNodeUids: Set<String>,
    ): Map<String, ThumbnailFailureKind> =
        if (deferredNodeUids.isEmpty()) failures else failures.filterKeys { nodeUid -> nodeUid !in deferredNodeUids }
}

internal object ThumbnailFailureClassifier {
    fun classify(error: Throwable): ThumbnailFailureKind {
        if (error is ProtonDriveSdkException) {
            error.error?.let { sdkError -> return classify(sdkError) }
            // Without a structured error the message is all the SDK gives, for example
            // "File thumbnail failure: This item has no image preview".
            return classifyDescription(error.message.orEmpty().lowercase())
        }
        val type = error::class.java.simpleName.lowercase()
        return if ("notfound" in type ||
            "not_found" in type
        ) {
            ThumbnailFailureKind.NOT_FOUND
        } else {
            ThumbnailFailureKind.OTHER
        }
    }

    /**
     * SDK failures all arrive as one exception class, so a missing rendition has to be recognised
     * from the structured error: typed data for missing nodes or renditions, HTTP 404, the API's
     * "does not exist" code, or the wording. Nested errors are consulted when the outer one is
     * undecided.
     */
    fun classify(sdkError: ProtonSdkError): ThumbnailFailureKind {
        when (sdkError.additionalData) {
            is ProtonSdkError.Data.NodeNotFound,
            is ProtonSdkError.Data.ThumbnailCountMismatch,
            is ProtonSdkError.Data.MissingContentBlock,
            -> return ThumbnailFailureKind.NOT_FOUND

            else -> Unit
        }
        if (sdkError.primaryCode == HTTP_NOT_FOUND || sdkError.secondaryCode == API_CODE_NOT_EXIST) {
            return ThumbnailFailureKind.NOT_FOUND
        }
        val description = "${sdkError.type.orEmpty()} ${sdkError.message.orEmpty()}".lowercase()
        if (classifyDescription(description) == ThumbnailFailureKind.NOT_FOUND) return ThumbnailFailureKind.NOT_FOUND
        return sdkError.innerError?.let(::classify) ?: ThumbnailFailureKind.OTHER
    }

    private fun classifyDescription(description: String): ThumbnailFailureKind =
        if (MISSING_RENDITION_PHRASES.any { phrase -> phrase in description }) {
            ThumbnailFailureKind.NOT_FOUND
        } else {
            ThumbnailFailureKind.OTHER
        }

    /** Wordings Proton uses when a photo simply has no such rendition; retrying cannot help. */
    private val MISSING_RENDITION_PHRASES =
        listOf("no image preview", "no preview", "no thumbnail", "not found", "notfound", "does not exist")

    private const val HTTP_NOT_FOUND = 404L

    /** Proton API response code for "the requested resource does not exist". */
    private const val API_CODE_NOT_EXIST = 2501L
}
