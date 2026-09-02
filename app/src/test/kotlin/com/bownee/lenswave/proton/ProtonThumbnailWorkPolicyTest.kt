package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonThumbnailWorkPolicyTest {
    @Test
    fun `complete download succeeds immediately`() {
        assertEquals(
            ProtonThumbnailWorkDecision.SUCCESS,
            ProtonThumbnailWorkPolicy.decide(runAttemptCount = 0, complete = true),
        )
    }

    @Test
    fun `incomplete download retries before the attempt limit`() {
        assertEquals(
            ProtonThumbnailWorkDecision.RETRY,
            ProtonThumbnailWorkPolicy.decide(
                runAttemptCount = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS - 2,
                complete = false,
            ),
        )
    }

    @Test
    fun `incomplete download fails at the attempt limit`() {
        assertEquals(
            ProtonThumbnailWorkDecision.FAILURE,
            ProtonThumbnailWorkPolicy.decide(
                runAttemptCount = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS - 1,
                complete = false,
            ),
        )
    }
}
