package com.bownee.lenswave

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LenswaveDiagnosticsTest {
    @Test
    fun workerStateContainsOnlyBoundedStructuredFields() {
        assertEquals(
            "operation=thumbnail-worker state=retry-timeout attempt=2 maximumAttempts=25",
            LenswaveDiagnostics.stateSummary(
                operation = "thumbnail-worker",
                state = "retry-timeout",
                attempt = 2,
                maximumAttempts = 25,
            ),
        )
    }

    @Test
    fun overlongOrUnsafeStateIsCutDownRatherThanRejected() {
        val longState = "open-beyond-end-position-" + "9".repeat(60)

        assertEquals(
            "operation=video-playback state=${longState.take(64)} attempt=1 maximumAttempts=1",
            LenswaveDiagnostics.stateSummary("video-playback", longState, 1, 1),
        )
        assertEquals(
            "operation=video-playback state=user_data_line_break attempt=1 maximumAttempts=1",
            LenswaveDiagnostics.stateSummary("video-playback", "user data\nline break", 1, 1),
        )
        assertEquals(
            "operation=unknown state=unknown attempt=1 maximumAttempts=1",
            LenswaveDiagnostics.stateSummary("", "", 1, 1),
        )
    }

    @Test
    fun protonFailureIncludesOnlyStructuredNonSensitiveFields() {
        val innerError =
            ProtonSdkError(
                message = "inner secret",
                type = "NetworkTimeout",
                domain = ProtonSdkError.ErrorDomain.Network,
                primaryCode = 408,
                secondaryCode = 2,
                context = "https://example.test/users/private",
            )
        val sdkError =
            ProtonSdkError(
                message = "outer secret",
                type = "ApiResponse",
                domain = ProtonSdkError.ErrorDomain.Api,
                primaryCode = 401,
                secondaryCode = 10,
                context =
                    """
                    java.lang.IllegalArgumentException: private-account-id
                        at retrofit2.RequestFactory.Builder.parseMethodAnnotation(RequestFactory.java:232)
                        at me.proton.drive.sdk.internal.ApiProviderBridge.execute(ApiProviderBridge.kt:117)
                        at unsafe.Exfiltrate.frame(/private/account:42)
                        at safe.Third.frame(Unknown Source)
                        at safe.Fourth.frame(Native Method)
                        at safe.Fifth.frame(Fifth.kt:5)
                    """.trimIndent(),
                innerError = innerError,
            )

        val summary =
            LenswaveDiagnostics.failureSummary(
                operation = "timeline-sync",
                error = ProtonDriveSdkException(error = sdkError),
            )

        assertEquals(
            "operation=timeline-sync failure=ProtonDriveSdkException " +
                "sdkDomain=Api sdkType=ApiResponse sdkPrimaryCode=401 sdkSecondaryCode=10 " +
                "innerDomain=Network innerType=NetworkTimeout innerPrimaryCode=408 innerSecondaryCode=2 " +
                "sdkFrame1=retrofit2.RequestFactory.Builder.parseMethodAnnotation(RequestFactory.java:232) " +
                "sdkFrame2=me.proton.drive.sdk.internal.ApiProviderBridge.execute(ApiProviderBridge.kt:117) " +
                "sdkFrame3=safe.Third.frame(Unknown Source) " +
                "sdkFrame4=safe.Fourth.frame(Native Method)",
            summary,
        )
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("example.test"))
        assertFalse(summary.contains("Exfiltrate"))
        assertFalse(summary.contains("Fifth"))
    }

    @Test
    fun unsafeSdkErrorTypeIsOmitted() {
        val sdkError =
            ProtonSdkError(
                message = "ignored",
                type = "type with user-data\nforged-log-line",
                domain = ProtonSdkError.ErrorDomain.BusinessLogic,
            )

        val summary =
            LenswaveDiagnostics.failureSummary(
                operation = "client-create",
                error = ProtonDriveSdkException(error = sdkError),
            )

        assertEquals(
            "operation=client-create failure=ProtonDriveSdkException sdkDomain=BusinessLogic",
            summary,
        )
    }

    @Test
    fun unstructuredProtonFailureKeepsOnlyATokenMessageAndTheCauseClass() {
        val tokenMessage =
            LenswaveDiagnostics.failureSummary(
                operation = "original-download",
                error = ProtonDriveSdkException(message = "DownloadTimeout", cause = java.io.IOException("socket")),
            )
        val freeFormMessage =
            LenswaveDiagnostics.failureSummary(
                operation = "original-download",
                error =
                    ProtonDriveSdkException(
                        message = "GET https://example.test/drive/private-node-uid failed: /data/user/0/private.jpg",
                    ),
            )

        assertEquals(
            "operation=original-download failure=ProtonDriveSdkException " +
                "sdkMessage=DownloadTimeout sdkCause=java.io.IOException",
            tokenMessage,
        )
        assertEquals("operation=original-download failure=ProtonDriveSdkException", freeFormMessage)
        assertFalse(freeFormMessage.contains("example.test"))
        assertFalse(freeFormMessage.contains("private"))
    }

    @Test
    fun ordinaryFailureRetainsItsClassName() {
        val summary = LenswaveDiagnostics.failureSummary("local-operation", IllegalStateException())

        assertEquals(
            "operation=local-operation failure=java.lang.IllegalStateException",
            summary,
        )
    }
}
