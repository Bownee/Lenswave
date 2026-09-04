package com.bownee.lenswave.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonThumbnailWorkerInstrumentedTest {
    @Test fun requestIsAccountKeyedAndWaitsForUnmeteredNetwork() {
        val request = ProtonThumbnailWorker.request(UserId("user"))
        assertEquals("user", request.workSpec.input.getString(ProtonThumbnailWorker.KEY_USER_ID))
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertFalse(request.workSpec.constraints.requiresCharging())
        assertEquals(0L, request.workSpec.initialDelay)
        assertTrue(request.workSpec.backoffDelayDuration >= 10_000L)
    }

    @Test fun followUpRequestCarriesTheChargerAndTheBackoff() {
        val request =
            ProtonThumbnailWorker.request(
                UserId("user"),
                requiresCharging = true,
                initialDelayMillis = 90_000L,
            )
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertEquals(90_000L, request.workSpec.initialDelay)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }
}
