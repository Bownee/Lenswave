package com.bownee.lenswave.proton

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private class AuthenticationException : RuntimeException()
    private class NotFoundException : RuntimeException()
}
