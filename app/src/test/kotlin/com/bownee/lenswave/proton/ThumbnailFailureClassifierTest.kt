package com.bownee.lenswave.proton

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.NodeUid
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ThumbnailFailureClassifierTest {
    @Test fun connectionFailuresAreTransientNetworkFailures() {
        listOf(
            SocketTimeoutException(),
            UnknownHostException("api.proton.me"),
            ConnectException(),
            SSLHandshakeException("reset"),
            IOException(),
        ).forEach { error ->
            assertEquals(
                error.toString(),
                ThumbnailFailureKind.TRANSIENT_NETWORK,
                ThumbnailFailureClassifier.classify(error),
            )
        }
    }

    @Test fun aConnectionFailureIsRecognisedThroughWrappers() {
        assertEquals(
            ThumbnailFailureKind.TRANSIENT_NETWORK,
            ThumbnailFailureClassifier.classify(RuntimeException("wrapped", UnknownHostException("host"))),
        )
        assertEquals(
            ThumbnailFailureKind.TRANSIENT_NETWORK,
            ThumbnailFailureClassifier.classify(ProtonDriveSdkException("failure", SocketTimeoutException(), null)),
        )
        // The wording still decides first: a missing rendition wrapped in an IO failure is missing.
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(ProtonDriveSdkException("no thumbnail", IOException(), null)),
        )
    }

    @Test fun theCauseChainIsWalkedOnlySoFar() {
        var error: Throwable = UnknownHostException("host")
        repeat(7) { error = RuntimeException("wrapped", error) }
        assertEquals(ThumbnailFailureKind.TRANSIENT_NETWORK, ThumbnailFailureClassifier.classify(error))
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFailureClassifier.classify(RuntimeException("one too deep", error)),
        )
    }

    @Test fun aNetworkDomainErrorWithoutAStatusCodeIsTransientAndWithOneIsNot() {
        assertEquals(
            ThumbnailFailureKind.TRANSIENT_NETWORK,
            ThumbnailFailureClassifier.classify(sdk(domain = ProtonSdkError.ErrorDomain.Network)),
        )
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFailureClassifier.classify(sdk(domain = ProtonSdkError.ErrorDomain.Network, primaryCode = 503L)),
        )
        assertEquals(
            ThumbnailFailureKind.TRANSIENT_NETWORK,
            ThumbnailFailureClassifier.classify(
                sdk(
                    domain = ProtonSdkError.ErrorDomain.BusinessLogic,
                    innerError = sdk(domain = ProtonSdkError.ErrorDomain.Network).error,
                ),
            ),
        )
    }

    @Test fun onlyMissingRenditionsAreNotFound() {
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFailureClassifier.classify(AuthenticationException()),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(NotFoundException()),
        )
    }

    @Test fun sdkMessageWithoutStructuredErrorIsClassifiedByWording() {
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(
                ProtonDriveSdkException("File thumbnail failure: This item has no image preview", null, null),
            ),
        )
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFailureClassifier.classify(ProtonDriveSdkException("File thumbnail failure: boom", null, null)),
        )
    }

    @Test fun sdkErrorsAreClassifiedFromTheirStructure() {
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(
                sdk(additionalData = ProtonSdkError.Data.NodeNotFound(NodeUid("node"))),
            ),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(sdk(domain = ProtonSdkError.ErrorDomain.Api, secondaryCode = 2501L)),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(sdk(primaryCode = 404L)),
        )
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFailureClassifier.classify(sdk(primaryCode = 401L)),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(
                sdk(domain = ProtonSdkError.ErrorDomain.BusinessLogic, innerError = sdk(primaryCode = 404L).error),
            ),
        )
        assertEquals(
            ThumbnailFailureKind.OTHER,
            ThumbnailFailureClassifier.classify(sdk(domain = ProtonSdkError.ErrorDomain.Cryptography)),
        )
    }

    private fun sdk(
        domain: ProtonSdkError.ErrorDomain = ProtonSdkError.ErrorDomain.Undefined,
        primaryCode: Long? = null,
        secondaryCode: Long? = null,
        innerError: ProtonSdkError? = null,
        additionalData: ProtonSdkError.Data<Any>? = null,
    ) = ProtonDriveSdkException(
        "failure",
        null,
        ProtonSdkError(
            message = "failure",
            type = "",
            domain = domain,
            primaryCode = primaryCode,
            secondaryCode = secondaryCode,
            context = null,
            innerError = innerError,
            additionalData = additionalData,
        ),
    )

    private class AuthenticationException : RuntimeException()

    private class NotFoundException : RuntimeException()
}
