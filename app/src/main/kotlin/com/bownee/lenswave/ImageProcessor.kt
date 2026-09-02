package com.bownee.lenswave

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object ImageProcessor {
    const val PREVIEW_LONG_EDGE = 2048
    private const val EXPORT_TILE_EDGE = 1024
    private const val MIN_EXPORT_PIXELS = 2_000_000L
    private const val MAX_EXPORT_PIXELS = 16_000_000L

    @Throws(IOException::class)
    fun decodePreview(context: Context, uri: Uri): Bitmap = decodeOriented(context, uri, PREVIEW_LONG_EDGE)

    @Throws(IOException::class)
    fun renderFullResolution(context: Context, uri: Uri, adjustments: PhotoAdjustments): Bitmap {
        val resolver = context.contentResolver
        val orientation = readOrientation(resolver, uri)
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val decoder = try {
                createRegionDecoder(descriptor)
            } catch (_: Exception) {
                val bounds = readBounds(resolver, uri)
                val cappedLongEdge = exportLongEdge(
                    bounds.first,
                    bounds.second,
                    Runtime.getRuntime().maxMemory(),
                )
                return renderExport(decodeOriented(context, uri, cappedLongEdge), adjustments)
            }
            try {
                val cappedLongEdge = exportLongEdge(decoder.width, decoder.height, Runtime.getRuntime().maxMemory())
                if (cappedLongEdge > 0) {
                    return renderExport(decodeOriented(context, uri, cappedLongEdge), adjustments)
                }
                return renderTiled(decoder, orientation, adjustments)
            } finally {
                @Suppress("DEPRECATION")
                decoder.recycle()
            }
        }
        throw IOException("The selected photo could not be opened.")
    }

    fun renderExport(bitmap: Bitmap, adjustments: PhotoAdjustments): Bitmap {
        adjustBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, adjustments)
        return if (adjustments.rotationQuarterTurns == 0) {
            bitmap
        } else {
            applyQuarterTurns(bitmap, adjustments.rotationQuarterTurns)
        }
    }

    @Throws(IOException::class)
    fun saveJpegCopy(context: Context, bitmap: Bitmap): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Lenswave_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Lenswave")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android could not create the output image.")
        try {
            resolver.openOutputStream(outputUri, "w")?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    throw IOException("The edited JPEG could not be written.")
                }
            } ?: throw IOException("The edited JPEG could not be written.")

            val completed = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(outputUri, completed, null, null) != 1) {
                throw IOException("Android could not publish the edited image.")
            }
            return outputUri
        } catch (error: Exception) {
            resolver.delete(outputUri, null, null)
            throw error
        }
    }

    @Throws(IOException::class)
    private fun decodeOriented(context: Context, uri: Uri, requestedLongEdge: Int): Bitmap {
        val resolver = context.contentResolver
        val (width, height) = readBounds(resolver, uri)

        val longEdge = max(width, height)
        var sampleSize = 1
        while (requestedLongEdge > 0 && longEdge / sampleSize > requestedLongEdge) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) }
            ?: throw IOException("The selected photo could not be decoded.")
        return ExifOrientation.apply(decoded, readOrientation(resolver, uri), false)
    }

    @Throws(IOException::class)
    private fun readBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = resolver.openInputStream(uri)
            ?: throw IOException("The selected photo could not be opened.")
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected file is not a supported image.")
        }
        return bounds.outWidth to bounds.outHeight
    }

    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    private fun createRegionDecoder(descriptor: ParcelFileDescriptor): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(descriptor)
        } else {
            BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
        }

    @Throws(IOException::class)
    private fun renderTiled(
        decoder: BitmapRegionDecoder,
        orientation: Int,
        adjustments: PhotoAdjustments,
    ): Bitmap {
        val imageWidth = decoder.width
        val imageHeight = decoder.height
        val outputSize = ImageTileLayout.outputSize(
            imageWidth,
            imageHeight,
            orientation,
            adjustments.rotationQuarterTurns,
        )
        val output = createBitmap(outputSize.width, outputSize.height)
        var completed = false
        try {
            val canvas = Canvas(output)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inScaled = false
            }
            var top = 0
            while (top < imageHeight) {
                throwIfInterrupted()
                val bottom = min(top + EXPORT_TILE_EDGE, imageHeight)
                var left = 0
                while (left < imageWidth) {
                    throwIfInterrupted()
                    val right = min(left + EXPORT_TILE_EDGE, imageWidth)
                    val region = Rect(left, top, right, bottom)
                    var working = decoder.decodeRegion(region, options)
                        ?: throw IOException("The selected photo could not be decoded at full resolution.")
                    try {
                        adjustBitmap(working, left, top, imageWidth, imageHeight, adjustments)
                        working = ExifOrientation.apply(working, orientation, false)
                        working = applyQuarterTurns(working, adjustments.rotationQuarterTurns)
                        val placement = ImageTileLayout.place(
                            left,
                            top,
                            region.width(),
                            region.height(),
                            imageWidth,
                            imageHeight,
                            orientation,
                            adjustments.rotationQuarterTurns,
                        )
                        if (working.width != placement.width || working.height != placement.height) {
                            throw IOException("The selected photo has an unsupported orientation.")
                        }
                        canvas.drawBitmap(working, placement.left.toFloat(), placement.top.toFloat(), null)
                    } finally {
                        if (!working.isRecycled) working.recycle()
                    }
                    left += EXPORT_TILE_EDGE
                }
                top += EXPORT_TILE_EDGE
            }
            completed = true
            return output
        } finally {
            if (!completed) output.recycle()
        }
    }

    private fun adjustBitmap(
        bitmap: Bitmap,
        sourceLeft: Int,
        sourceTop: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        adjustments: PhotoAdjustments,
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        for (y in 0 until height) {
            throwIfInterrupted()
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val sourceY = sourceTop + y
            val normalizedY = if (sourceHeight == 1) 0.5f else sourceY.toFloat() / (sourceHeight - 1)
            for (x in 0 until width) {
                val sourceX = sourceLeft + x
                val normalizedX = if (sourceWidth == 1) 0.5f else sourceX.toFloat() / (sourceWidth - 1)
                row[x] = PixelMath.adjustPixel(row[x], normalizedX, normalizedY, adjustments)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun applyQuarterTurns(source: Bitmap, rotationQuarterTurns: Int): Bitmap {
        val normalizedTurns = Math.floorMod(rotationQuarterTurns, 4)
        if (normalizedTurns == 0) return source
        val rotation = Matrix().apply { postRotate(normalizedTurns * 90f) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotation, false)
        if (rotated !== source) source.recycle()
        return rotated
    }

    fun exportLongEdge(width: Int, height: Int, maxHeapBytes: Long): Int {
        if (width <= 0 || height <= 0) return 0
        val pixelCount = width.toLong() * height
        val heapBudgetPixels = max(MIN_EXPORT_PIXELS, maxHeapBytes / 16L)
        val pixelBudget = min(MAX_EXPORT_PIXELS, heapBudgetPixels)
        if (pixelCount <= pixelBudget) return 0
        val scale = sqrt(pixelBudget.toDouble() / pixelCount)
        return max(1, floor(max(width, height) * scale).toInt())
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Image export cancelled")
    }
}
