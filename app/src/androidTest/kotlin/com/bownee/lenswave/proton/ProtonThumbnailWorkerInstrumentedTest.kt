package com.bownee.lenswave.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonThumbnailWorkerInstrumentedTest {
    @Test fun requestIsAccountKeyedAndNotStoppedByDozeNetworkTracking() {
        val request = ProtonThumbnailWorker.request(UserId("user"))
        assertEquals("user", request.workSpec.input.getString(ProtonThumbnailWorker.KEY_USER_ID))
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertTrue(request.workSpec.backoffDelayDuration >= 10_000L)
    }
}
