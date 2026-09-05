package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtonPhotoIndexCodecTest {
    private val photos =
        listOf(
            ProtonGalleryPhoto(nodeUid = "v~a1", captureTimeEpochSeconds = 1_700_000_000L, hasThumbnail = true),
            ProtonGalleryPhoto(nodeUid = "v~b2", captureTimeEpochSeconds = -5L, hasThumbnail = false),
            ProtonGalleryPhoto(nodeUid = "ü/=", captureTimeEpochSeconds = Long.MAX_VALUE, hasThumbnail = false),
        )

    private fun decode(bytes: ByteArray): List<Pair<String, Long>> =
        ProtonPhotoIndexCodec.decode(bytes) { nodeUid, captureTime -> nodeUid to captureTime }

    @Test
    fun `a listing survives the round trip in stored order without its availability`() {
        val bytes = ProtonPhotoIndexCodec.encode(photos)

        assertFalse(ProtonPhotoIndexCodec.isLegacyJson(bytes))
        assertEquals(photos.map { it.nodeUid to it.captureTimeEpochSeconds }, decode(bytes))
    }

    @Test
    fun `an empty listing round-trips`() {
        assertEquals(emptyList<Pair<String, Long>>(), decode(ProtonPhotoIndexCodec.encode(emptyList())))
    }

    @Test
    fun `a truncated listing is corrupt rather than partially read`() {
        val bytes = ProtonPhotoIndexCodec.encode(photos)

        assertThrows(CorruptPhotoIndexException::class.java) { decode(bytes.copyOf(bytes.size - 3)) }
        assertThrows(CorruptPhotoIndexException::class.java) { decode(bytes.copyOf(6)) }
    }

    @Test
    fun `bytes of another kind are corrupt`() {
        assertThrows(
            CorruptPhotoIndexException::class.java,
        ) { decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)) }
        assertThrows(CorruptPhotoIndexException::class.java) { decode(ByteArray(0)) }
    }

    @Test
    fun `a listing that claims a negative count is corrupt`() {
        val bytes = ProtonPhotoIndexCodec.encode(emptyList())
        bytes[8] = 0xFF.toByte()

        assertThrows(CorruptPhotoIndexException::class.java) { decode(bytes) }
    }
}
