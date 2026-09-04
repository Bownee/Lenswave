package com.bownee.lenswave.proton

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.NodeUid
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ThumbnailFailureClassifierTest {
    @Test fun timeoutIsRetryableNetworkFailure() {
        assertEquals(
            ThumbnailFailureKind.NETWORK,
            ThumbnailFailureClassifier.classify(SocketTimeoutException()),
        )
    }

    @Test fun authenticationAndNotFoundStayDistinct() {
        assertEquals(
            ThumbnailFailureKind.AUTHENTICATION,
            ThumbnailFailureClassifier.classify(AuthenticationException()),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(NotFoundException()),
        )
    }

    @Test fun ordinaryIoFailureIsNetworkFailure() {
        assertEquals(
            ThumbnailFailureKind.NETWORK,
            ThumbnailFailureClassifier.classify(IOException()),
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
            ThumbnailFailureKind.AUTHENTICATION,
            ThumbnailFailureClassifier.classify(sdk(primaryCode = 401L)),
        )
        assertEquals(
            ThumbnailFailureKind.NETWORK,
            ThumbnailFailureClassifier.classify(sdk(domain = ProtonSdkError.ErrorDomain.Network)),
        )
        assertEquals(
            ThumbnailFailureKind.NOT_FOUND,
            ThumbnailFailureClassifier.classify(
                sdk(domain = ProtonSdkError.ErrorDomain.BusinessLogic, innerError = sdk(primaryCode = 404L).error),
            ),
        )
        assertEquals(
            ThumbnailFailureKind.UNKNOWN,
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
