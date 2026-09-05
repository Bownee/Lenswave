package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailCodecTest {
    @Test
    fun subsampledBytesAreReencoded() {
        assertTrue(ProtonThumbnailCodec.needsReencode(sampleSize = 2, rescaled = false))
        assertTrue(ProtonThumbnailCodec.needsReencode(sampleSize = 4, rescaled = true))
    }

    @Test
    fun bytesThatWereOnlyScaledDownAreReencodedToo() {
        // A 700 px source is not subsampled (the next power of two would undershoot the grid)
        // but is scaled to 480 px; storing the delivered bytes would decode 700 px on every load.
        assertTrue(ProtonThumbnailCodec.needsReencode(sampleSize = 1, rescaled = true))
    }

    @Test
    fun bytesAlreadyAtGridSizeAreStoredAsDelivered() {
        assertFalse(ProtonThumbnailCodec.needsReencode(sampleSize = 1, rescaled = false))
    }
}
