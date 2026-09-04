package com.bownee.lenswave.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoMetadataHintsTest {
    @Test
    fun `hinted dimensions follow the exif rotation`() {
        assertEquals(4_000 to 3_000, PhotoMetadataDimensionPolicy.oriented(4_000, 3_000, 0))
        assertEquals(3_000 to 4_000, PhotoMetadataDimensionPolicy.oriented(4_000, 3_000, 90))
        assertEquals(4_000 to 3_000, PhotoMetadataDimensionPolicy.oriented(4_000, 3_000, 180))
        assertEquals(3_000 to 4_000, PhotoMetadataDimensionPolicy.oriented(4_000, 3_000, 270))
    }

    @Test
    fun `hints carry what the viewer decoded`() {
        val hints =
            PhotoMetadataHints(rawWidth = 6_000, rawHeight = 4_000, rotationDegrees = 90, mimeType = "image/jpeg")

        assertEquals(
            4_000 to 6_000,
            PhotoMetadataDimensionPolicy.oriented(hints.rawWidth, hints.rawHeight, hints.rotationDegrees),
        )
        assertEquals("image/jpeg", hints.mimeType)
    }

    @Test
    fun `mime is sniffed from the container signature`() {
        assertEquals("image/jpeg", ImageMimeSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(
            "image/png",
            ImageMimeSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)),
        )
        assertEquals("image/gif", ImageMimeSniffer.sniff("GIF89a".toByteArray()))
        assertEquals("image/bmp", ImageMimeSniffer.sniff("BM".toByteArray()))
        assertEquals("image/webp", ImageMimeSniffer.sniff("RIFF....WEBP".toByteArray()))
        assertEquals("image/heic", ImageMimeSniffer.sniff(isoBox("heic")))
        assertEquals("image/heif", ImageMimeSniffer.sniff(isoBox("mif1")))
        assertEquals("image/avif", ImageMimeSniffer.sniff(isoBox("avif")))
    }

    @Test
    fun `unknown or short headers yield no mime`() {
        assertNull(ImageMimeSniffer.sniff(ByteArray(0)))
        assertNull(ImageMimeSniffer.sniff(bytes(0xFF, 0xD8)))
        assertNull(ImageMimeSniffer.sniff(isoBox("mp42")))
        assertNull(ImageMimeSniffer.sniff("RIFF....WAVE".toByteArray()))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** A 24-byte `ftyp` box header with the given major brand, as ISO base media files start. */
    private fun isoBox(brand: String): ByteArray = bytes(0, 0, 0, 0x18) + "ftyp$brand".toByteArray()
}
