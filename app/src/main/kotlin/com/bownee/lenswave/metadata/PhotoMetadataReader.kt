package com.bownee.lenswave.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import androidx.exifinterface.media.ExifInterface
import com.bownee.lenswave.ExifOrientation
import com.bownee.lenswave.R
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
    data class OpenMap(
        val latitude: Double,
        val longitude: Double,
    ) : PhotoMetadataAction
}

data class PhotoLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
) {
    fun coordinateText(): String = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
}

/** Builds the rows of the photo details sheet from a decrypted original on local storage. */
class PhotoMetadataReader
    @Inject
    constructor() {
        /**
         * Reads the rows for the original at [uri]. With [hints] from the viewer's own decode the
         * file is opened once, for the EXIF tags; without them a bounds decode supplies the
         * dimensions and the format and the EXIF orientation is derived here.
         */
        fun read(
            context: Context,
            uri: Uri,
            fallbackName: String,
            fallbackTimestamp: Long,
            hints: PhotoMetadataHints? = null,
        ): List<PhotoMetadataItem> {
            val resolver = context.contentResolver
            val size = if (uri.scheme == "file") File(requireNotNull(uri.path)).length() else 0L
            val exif =
                runCatching {
                    resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        ExifSnapshot.from(ExifInterface(descriptor.fileDescriptor), readOrientation = hints == null)
                    }
                }.getOrNull()
            val rawWidth: Int
            val rawHeight: Int
            val rotationDegrees: Int
            val detectedMimeType: String?
            if (hints != null) {
                rawWidth = hints.rawWidth
                rawHeight = hints.rawHeight
                rotationDegrees = hints.rotationDegrees
                detectedMimeType = hints.mimeType
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching {
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                }
                rawWidth = bounds.outWidth.takeIf { it > 0 } ?: exif?.width.orZero()
                rawHeight = bounds.outHeight.takeIf { it > 0 } ?: exif?.height.orZero()
                rotationDegrees = exif?.rotationDegrees.orZero()
                detectedMimeType = bounds.outMimeType
            }
            val mimeType = resolver.getType(uri).orEmpty().ifBlank { detectedMimeType.orEmpty() }
            val (width, height) = PhotoMetadataDimensionPolicy.oriented(rawWidth, rawHeight, rotationDegrees)

            return buildList {
                fun row(
                    labelRes: Int,
                    value: String?,
                    action: PhotoMetadataAction? = null,
                ) {
                    if (value.isNullOrBlank()) return
                    add(PhotoMetadataItem(context.getString(labelRes), value, action))
                }
                row(R.string.metadata_file_name, fallbackName)
                row(
                    R.string.metadata_captured,
                    fallbackTimestamp.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) },
                )
                if (width > 0 && height > 0) {
                    row(
                        R.string.metadata_dimensions,
                        context.getString(R.string.metadata_dimensions_value, width, height),
                    )
                }
                row(
                    R.string.metadata_file_size,
                    size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                )
                row(R.string.metadata_format, mimeType)
                row(R.string.metadata_camera, exif?.camera)
                row(R.string.metadata_lens, exif?.lens)
                row(
                    R.string.metadata_aperture,
                    exif?.aperture?.let { context.getString(R.string.metadata_aperture_value, it) },
                )
                row(
                    R.string.metadata_exposure,
                    exif?.exposure?.let { context.getString(R.string.metadata_exposure_value, it) },
                )
                row(R.string.metadata_iso, exif?.iso)
                row(
                    R.string.metadata_focal_length,
                    exif?.focalLength?.let { context.getString(R.string.metadata_focal_length_value, it) },
                )
                row(R.string.metadata_description, exif?.description)
                row(R.string.metadata_artist, exif?.artist)
                row(R.string.metadata_copyright, exif?.copyright)
                exif?.location?.let { location ->
                    row(
                        R.string.metadata_coordinates,
                        location.coordinateText(),
                        PhotoMetadataAction.OpenMap(location.latitude, location.longitude),
                    )
                    row(
                        R.string.metadata_gps_altitude,
                        location.altitudeMeters?.let { context.getString(R.string.metadata_gps_altitude_value, it) },
                    )
                }
                row(
                    R.string.metadata_gps_direction,
                    exif?.gpsDirection?.let { context.getString(R.string.metadata_gps_direction_value, it) },
                )
            }
        }

        private fun Int?.orZero(): Int = this ?: 0

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
                fun from(
                    exif: ExifInterface,
                    readOrientation: Boolean,
                ): ExifSnapshot {
                    val orientationValue =
                        if (readOrientation) {
                            exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                        } else {
                            ExifInterface.ORIENTATION_NORMAL
                        }
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
    }
