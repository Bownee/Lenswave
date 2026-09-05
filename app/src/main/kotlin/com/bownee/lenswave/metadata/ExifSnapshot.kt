package com.bownee.lenswave.metadata

import androidx.exifinterface.media.ExifInterface
import com.bownee.lenswave.ExifOrientation
import java.util.Locale

/**
 * The EXIF values the details sheet shows, lifted out of a parsed [ExifInterface] so the parse
 * happens once: the viewer's decode already opens the file for its orientation, and the sheet
 * takes what that parse found rather than opening the file again.
 */
data class ExifSnapshot(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val camera: String?,
    val lens: String?,
    val aperture: String?,
    val exposure: String?,
    val iso: String?,
    val focalLength: String?,
    val description: String?,
    val artist: String?,
    val copyright: String?,
    val location: PhotoLocation?,
    val gpsDirection: String?,
) {
    companion object {
        /** [orientationValue] is the raw EXIF orientation the caller read, or ORIENTATION_NORMAL to ignore it. */
        fun from(
            exif: ExifInterface,
            orientationValue: Int,
        ): ExifSnapshot {
            val coordinates = exif.latLong
            val altitude = exif.getAltitude(Double.NaN).takeUnless(Double::isNaN)
            val location =
                if (coordinates != null) {
                    PhotoLocation(
                        latitude = coordinates[0],
                        longitude = coordinates[1],
                        altitudeMeters = altitude,
                    )
                } else {
                    null
                }
            return ExifSnapshot(
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                rotationDegrees = ExifOrientation.degrees(orientationValue),
                camera =
                    listOfNotNull(
                        exif.attribute(ExifInterface.TAG_MAKE),
                        exif.attribute(ExifInterface.TAG_MODEL),
                    ).joinToString(" ").ifBlank { null },
                lens = exif.attribute("LensModel"),
                aperture = exif.decimalAttribute(ExifInterface.TAG_F_NUMBER),
                exposure =
                    exif
                        .getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
                        .let(ExifValueFormatter::exposureTime),
                iso =
                    exif.attribute("PhotographicSensitivity")
                        ?: exif.attribute("ISOSpeedRatings"),
                focalLength = exif.decimalAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                description = exif.attribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
                artist = exif.attribute(ExifInterface.TAG_ARTIST),
                copyright = exif.attribute(ExifInterface.TAG_COPYRIGHT),
                location = location,
                gpsDirection = exif.decimalAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION),
            )
        }

        private fun ExifInterface.attribute(tag: String): String? =
            getAttribute(tag)?.trim()?.takeIf(String::isNotBlank)

        private fun ExifInterface.decimalAttribute(tag: String): String? = attribute(tag)?.let(::formatRational)

        private fun formatRational(value: String): String {
            val parts = value.split('/')
            if (parts.size != 2) return value
            val numerator = parts[0].toDoubleOrNull() ?: return value
            val denominator = parts[1].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return value
            return String
                .format(Locale.US, "%.2f", numerator / denominator)
                .trimEnd('0')
                .trimEnd('.')
        }
    }
}
