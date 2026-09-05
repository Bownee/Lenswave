package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtonPreviewDecodePolicyTest {
    @Test
    fun aPreviewSmallerThanTheDisplayIsDecodedAtFullSize() {
        assertEquals(1, ProtonPreviewDecodePolicy.sampleSize(width = 1_920, height = 1_440, targetLongEdge = 2_400))
    }

    @Test
    fun aPreviewLargerThanTheDisplayIsSubsampledWithoutDroppingBelowTheTarget() {
        assertEquals(2, ProtonPreviewDecodePolicy.sampleSize(width = 1_440, height = 1_920, targetLongEdge = 960))
        assertEquals(1, ProtonPreviewDecodePolicy.sampleSize(width = 1_440, height = 1_920, targetLongEdge = 961))
        assertEquals(4, ProtonPreviewDecodePolicy.sampleSize(width = 4_000, height = 3_000, targetLongEdge = 1_000))
    }

    @Test
    fun invalidDimensionsFallBackToFullSize() {
        assertEquals(1, ProtonPreviewDecodePolicy.sampleSize(width = 0, height = 0, targetLongEdge = 1_000))
        assertEquals(1, ProtonPreviewDecodePolicy.sampleSize(width = 1_920, height = 1_080, targetLongEdge = 0))
    }
}
