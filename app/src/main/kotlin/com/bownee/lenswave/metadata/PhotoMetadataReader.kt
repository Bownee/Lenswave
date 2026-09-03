package com.bownee.lenswave.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class PhotoMetadataItem(
    val label: String,
    val value: String,
    val action: PhotoMetadataAction? = null,
)

sealed interface PhotoMetadataAction {
    data class OpenMap(val latitude: Double, val longitude: Double) : PhotoMetadataAction
}

data class PhotoLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
) {
    fun coordinateText(): String = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
}

class PhotoMetadataReader @Inject constructor() {
    fun read(
        context: Context,
        uri: Uri,
        fallbackName: String,
        fallbackTimestamp: Long,
    ): List<PhotoMetadataItem> {
        val resolver = context.contentResolver
        var displayName = fallbackName
        var size = 0L
        var capturedAt = fallbackTimestamp
        runCatching {
            resolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.Images.Media.DATE_TAKEN,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { column ->
                        displayName = cursor.getString(column).orEmpty().ifBlank { displayName }
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { column ->
                        size = cursor.getLong(column)
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN).takeIf { it >= 0 }?.let { column ->
                        capturedAt = cursor.getLong(column).takeIf { it > 0 } ?: capturedAt
                    }
                }
            }
        }
        if (uri.scheme == "file") {
            size = size.takeIf { it > 0 } ?: File(requireNotNull(uri.path)).length()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val exif = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                ExifSnapshot.from(ExifInterface(descriptor.fileDescriptor))
            }
        }.getOrNull()
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { bounds.outMimeType.orEmpty() }
        val rawWidth = bounds.outWidth.takeIf { it > 0 } ?: exif?.width.orZero()
        val rawHeight = bounds.outHeight.takeIf { it > 0 } ?: exif?.height.orZero()
        val rotated = exif?.rotationDegrees in setOf(90, 270)
        val width = if (rotated) rawHeight else rawWidth
        val height = if (rotated) rawWidth else rawHeight

        return buildList {
            displayName.takeIf(String::isNotBlank)?.let { add(PhotoMetadataItem("File name", it)) }
            capturedAt.takeIf { it > 0 }?.let {
                add(PhotoMetadataItem("Captured", DateFormat.getDateTimeInstance().format(Date(it))))
            }
            if (width > 0 && height > 0) add(PhotoMetadataItem("Dimensions", "$width × $height px"))
            size.takeIf { it > 0 }?.let { add(PhotoMetadataItem("File size", formatBytes(it))) }
            mimeType.takeIf(String::isNotBlank)?.let { add(PhotoMetadataItem("Format", it)) }
            exif?.camera.takeIfNotBlank()?.let { add(PhotoMetadataItem("Camera", it)) }
            exif?.lens.takeIfNotBlank()?.let { add(PhotoMetadataItem("Lens", it)) }
            exif?.aperture.takeIfNotBlank()?.let { add(PhotoMetadataItem("Aperture", "f/$it")) }
            exif?.exposure.takeIfNotBlank()?.let { add(PhotoMetadataItem("Exposure", "$it s")) }
            exif?.iso.takeIfNotBlank()?.let { add(PhotoMetadataItem("ISO", it)) }
            exif?.focalLength.takeIfNotBlank()?.let { add(PhotoMetadataItem("Focal length", "$it mm")) }
            exif?.description.takeIfNotBlank()?.let { add(PhotoMetadataItem("Description", it)) }
            exif?.artist.takeIfNotBlank()?.let { add(PhotoMetadataItem("Artist", it)) }
            exif?.copyright.takeIfNotBlank()?.let { add(PhotoMetadataItem("Copyright", it)) }
            exif?.location?.let { location ->
                add(PhotoMetadataItem(
                    label = "Coordinates",
                    value = location.coordinateText(),
                    action = PhotoMetadataAction.OpenMap(location.latitude, location.longitude),
                ))
                location.altitudeMeters?.let { altitude ->
                    add(PhotoMetadataItem("GPS altitude", String.format(Locale.getDefault(), "%.1f m", altitude)))
                }
            }
            exif?.gpsDirection.takeIfNotBlank()?.let { add(PhotoMetadataItem("GPS direction", "$it°")) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1_024.0
        var unit = 0
        while (value >= 1_024 && unit < units.lastIndex) {
            value /= 1_024
            unit++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun String?.takeIfNotBlank(): String? = this?.takeIf(String::isNotBlank)

    private data class ExifSnapshot(
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
            fun from(exif: ExifInterface): ExifSnapshot {
                val orientationValue = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
                val coordinates = exif.latLong
                val altitude = exif.getAltitude(Double.NaN).takeUnless(Double::isNaN)
                val location = if (coordinates != null) {
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
                    rotationDegrees = orientationDegrees(orientationValue),
                    camera = listOfNotNull(
                        exif.attribute(ExifInterface.TAG_MAKE),
                        exif.attribute(ExifInterface.TAG_MODEL),
                    ).joinToString(" ").ifBlank { null },
                    lens = exif.attribute("LensModel"),
                    aperture = exif.decimalAttribute(ExifInterface.TAG_F_NUMBER),
                    exposure = exif.attribute(ExifInterface.TAG_EXPOSURE_TIME),
                    iso = exif.attribute("PhotographicSensitivity")
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

            private fun ExifInterface.decimalAttribute(tag: String): String? =
                attribute(tag)?.let(::formatRational)

            private fun formatRational(value: String): String {
                val parts = value.split('/')
                if (parts.size != 2) return value
                val numerator = parts[0].toDoubleOrNull() ?: return value
                val denominator = parts[1].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return value
                return String.format(Locale.US, "%.2f", numerator / denominator)
                    .trimEnd('0')
                    .trimEnd('.')
            }

            private fun orientationDegrees(value: Int): Int = when (value) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }

        }
    }
}
