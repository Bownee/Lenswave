package com.bownee.lenswave.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import androidx.exifinterface.media.ExifInterface
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
         * file is not opened at all: the dimensions, the format and the EXIF snapshot come from
         * the parse the decode already did. Without them the file is opened for the EXIF tags, a
         * bounds decode supplies the dimensions and the format, and the orientation is derived here.
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
                hints?.exif
                    ?: runCatching {
                        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            val parsed = ExifInterface(descriptor.fileDescriptor)
                            ExifSnapshot.from(
                                parsed,
                                orientationValue =
                                    if (hints == null) {
                                        parsed.getAttributeInt(
                                            ExifInterface.TAG_ORIENTATION,
                                            ExifInterface.ORIENTATION_NORMAL,
                                        )
                                    } else {
                                        ExifInterface.ORIENTATION_NORMAL
                                    },
                            )
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
                detectedMimeType = bounds.outMimeType
                rotationDegrees =
                    ImageOrientationPolicy.effectiveRotationDegrees(detectedMimeType, exif?.rotationDegrees.orZero())
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
                row(R.string.metadata_captured, fallbackTimestamp.takeIf { it > 0 }?.let(::formatCapturedAt))
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

        /** The last date format built, valid for the locale it was built for. */
        @Volatile
        private var capturedAtFormat: Pair<Locale, DateFormat>? = null

        /**
         * Formats with a DateFormat cached per locale; java.text formats are not thread-safe, so
         * concurrent reads on the IO dispatcher take turns on it.
         */
        private fun formatCapturedAt(epochMillis: Long): String {
            val locale = Locale.getDefault(Locale.Category.FORMAT)
            val format =
                capturedAtFormat?.takeIf { it.first == locale }?.second
                    ?: DateFormat.getDateTimeInstance().also { capturedAtFormat = locale to it }
            return synchronized(format) { format.format(Date(epochMillis)) }
        }

        private fun Int?.orZero(): Int = this ?: 0
    }
