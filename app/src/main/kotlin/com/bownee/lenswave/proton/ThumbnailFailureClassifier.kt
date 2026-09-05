package com.bownee.lenswave.proton

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import java.io.IOException

/**
 * Only four failures change what happens next: a missing rendition gets the thumbnail as its
 * preview, an unanswered node is asked again on its own, a node the network failed under is
 * retried shortly without being charged, and everything else backs off. A node that met several
 * failures in one batch keeps the one with the highest [priority].
 */
internal enum class ThumbnailFailureKind(
    val priority: Int,
) {
    OTHER(0),

    /** The SDK gave no answer for the node before the pass ended; asked again on its own first. */
    UNANSWERED(1),

    /**
     * The connection failed under the pass (no route, a reset, a socket timeout): the node is
     * not at fault and takes no backoff step, only a short pause. Outranks [UNANSWERED] because
     * re-asking a node on its own over a dead network costs a whole deadline for nothing.
     */
    TRANSIENT_NETWORK(2),

    /** Proton has no such rendition for the photo; retrying cannot help. */
    NOT_FOUND(3),

    /**
     * Not a failure the classifier ever produces: the sync settles a node with it when the
     * preview that stands in for its missing thumbnail could not be fetched because previews
     * were not allowed. The queue parks the node without a backoff step until a run that may
     * fetch previews ([ProtonThumbnailQueue.settle]).
     */
    PREVIEW_DEFERRED(4),
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
     * Such nodes are reported as deferred ([ThumbnailBatchResult.deferredNodeUids]) and parked
     * in the queue until a run that may fetch previews.
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
            val byDescription = classifyDescription(error.message.orEmpty().lowercase())
            if (byDescription != ThumbnailFailureKind.OTHER) return byDescription
            return if (isNetworkFailure(error.cause)) ThumbnailFailureKind.TRANSIENT_NETWORK else byDescription
        }
        val type = error::class.java.simpleName.lowercase()
        return if ("notfound" in type ||
            "not_found" in type
        ) {
            ThumbnailFailureKind.NOT_FOUND
        } else if (isNetworkFailure(error)) {
            ThumbnailFailureKind.TRANSIENT_NETWORK
        } else {
            ThumbnailFailureKind.OTHER
        }
    }

    /**
     * Every failure of the connection itself is an [IOException]: no route or host
     * ([java.net.UnknownHostException], [java.net.ConnectException]), a reset, a socket timeout,
     * a TLS handshake that never completed. The cause chain is walked because the SDK and the
     * HTTP client wrap what the socket threw.
     */
    private fun isNetworkFailure(error: Throwable?): Boolean =
        generateSequence(error, Throwable::cause).take(MAX_CAUSE_DEPTH).any { cause -> cause is IOException }

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
        sdkError.innerError
            ?.let(::classify)
            ?.takeUnless { kind ->
                kind == ThumbnailFailureKind.OTHER
            }?.let { return it }
        // A network-domain error without a status code never reached the server: the
        // connection failed. One with a code is the server's answer (a 5xx, a 429) and backs off.
        return if (sdkError.domain == ProtonSdkError.ErrorDomain.Network && sdkError.primaryCode == null) {
            ThumbnailFailureKind.TRANSIENT_NETWORK
        } else {
            ThumbnailFailureKind.OTHER
        }
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

    /** Cause chains are short; the bound only guards against a cycle. */
    private const val MAX_CAUSE_DEPTH = 8

    /** Proton API response code for "the requested resource does not exist". */
    private const val API_CODE_NOT_EXIST = 2501L
}
