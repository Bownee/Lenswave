package com.bownee.lenswave.metadata

/**
 * What the viewer already knows about the decoded original, so [PhotoMetadataReader] does not
 * open the file again for a bounds decode or derive the orientation a second time.
 */
data class PhotoMetadataHints(
    val rawWidth: Int,
    val rawHeight: Int,
    val rotationDegrees: Int,
    val mimeType: String?,
)

/**
 * Whether the EXIF orientation tag still has to be applied to what the platform decoder returns.
 * JPEG, TIFF, PNG and WebP come back as stored, so the tag describes a rotation nobody has done
 * yet. The HEIF family (HEIC, HEIF, AVIF) carries its rotation in container boxes that the decoder
 * honours itself, so applying the tag on top would rotate the picture twice and report swapped
 * dimensions in the details sheet.
 */
internal object ImageOrientationPolicy {
    /** The EXIF orientation value meaning "as stored" (ExifInterface.ORIENTATION_NORMAL). */
    const val ORIENTATION_NORMAL = 1

    fun appliesExifOrientation(mimeType: String?): Boolean =
        when (mimeType?.lowercase()) {
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence", "image/avif" -> false
            else -> true
        }

    /** The orientation the viewer should apply to decoded pixels of a [mimeType] file. */
    fun effectiveOrientation(
        mimeType: String?,
        exifOrientation: Int,
    ): Int = if (appliesExifOrientation(mimeType)) exifOrientation else ORIENTATION_NORMAL

    /** The rotation the details sheet should combine with decoder dimensions of a [mimeType] file. */
    fun effectiveRotationDegrees(
        mimeType: String?,
        exifRotationDegrees: Int,
    ): Int = if (appliesExifOrientation(mimeType)) exifRotationDegrees else 0
}

/** Pure sizing rules for the dimensions row of the details sheet. */
internal object PhotoMetadataDimensionPolicy {
    /** Width and height as displayed, once the EXIF rotation is applied to the stored pixels. */
    fun oriented(
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
    ): Pair<Int, Int> = if (rotationDegrees % 180 == 90) rawHeight to rawWidth else rawWidth to rawHeight
}

/** Recognises an image container from its first bytes; a header parse costs nothing like a decode. */
internal object ImageMimeSniffer {
    /** Bytes needed to tell the supported containers apart. */
    const val HEADER_LENGTH = 12

    fun sniff(header: ByteArray): String? {
        if (header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (header.startsWith(PNG_SIGNATURE)) return "image/png"
        if (header.startsWith("GIF8".toByteArray(Charsets.US_ASCII))) return "image/gif"
        if (header.startsWith("BM".toByteArray(Charsets.US_ASCII))) return "image/bmp"
        if (header.size >= 12 &&
            header.startsWith("RIFF".toByteArray(Charsets.US_ASCII)) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
        ) {
            return "image/webp"
        }
        if (header.size >= 12 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))) {
            return when (String(header, 8, 4, Charsets.US_ASCII)) {
                "heic", "heix", "hevc", "hevx" -> "image/heic"
                "mif1", "msf1", "heif" -> "image/heif"
                "avif", "avis" -> "image/avif"
                else -> null
            }
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
