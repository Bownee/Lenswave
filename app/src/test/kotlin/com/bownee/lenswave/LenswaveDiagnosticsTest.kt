package com.bownee.lenswave

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LenswaveDiagnosticsTest {
    @Test
    fun protonFailureIncludesOnlyStructuredNonSensitiveFields() {
        val innerError = ProtonSdkError(
            message = "inner secret",
            type = "NetworkTimeout",
            domain = ProtonSdkError.ErrorDomain.Network,
            primaryCode = 408,
            secondaryCode = 2,
            context = "https://example.test/users/private",
        )
        val sdkError = ProtonSdkError(
            message = "outer secret",
            type = "ApiResponse",
            domain = ProtonSdkError.ErrorDomain.Api,
            primaryCode = 401,
            secondaryCode = 10,
            context = "private-account-id",
            innerError = innerError,
        )

        val summary = LenswaveDiagnostics.failureSummary(
            operation = "timeline-sync",
            error = ProtonDriveSdkException(error = sdkError),
        )

        assertEquals(
            "operation=timeline-sync failure=ProtonDriveSdkException " +
                "sdkDomain=Api sdkType=ApiResponse sdkPrimaryCode=401 sdkSecondaryCode=10 " +
                "innerDomain=Network innerType=NetworkTimeout innerPrimaryCode=408 innerSecondaryCode=2",
            summary,
        )
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("example.test"))
    }

    @Test
    fun unsafeSdkErrorTypeIsOmitted() {
        val sdkError = ProtonSdkError(
            message = "ignored",
            type = "type with user-data\nforged-log-line",
            domain = ProtonSdkError.ErrorDomain.BusinessLogic,
        )

        val summary = LenswaveDiagnostics.failureSummary(
            operation = "client-create",
            error = ProtonDriveSdkException(error = sdkError),
        )

        assertEquals(
            "operation=client-create failure=ProtonDriveSdkException sdkDomain=BusinessLogic",
            summary,
        )
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
