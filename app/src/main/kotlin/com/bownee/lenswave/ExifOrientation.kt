package com.bownee.lenswave

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface

internal object ExifOrientation {
    fun apply(
        source: Bitmap,
        orientation: Int,
        filter: Boolean,
    ): Bitmap {
        val matrix = matrix(orientation) ?: return source
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, filter)
        if (result !== source) source.recycle()
        return result
    }

    fun degrees(orientation: Int): Int =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE,
            -> 90

            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            -> 180

            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE,
            -> 270

            else -> 0
        }

    fun orientedToRaw(
        rect: Rect,
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
    ): Rect =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                Rect(rawWidth - rect.right, rect.top, rawWidth - rect.left, rect.bottom)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                Rect(rawWidth - rect.right, rawHeight - rect.bottom, rawWidth - rect.left, rawHeight - rect.top)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                Rect(rect.left, rawHeight - rect.bottom, rect.right, rawHeight - rect.top)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                Rect(rect.top, rect.left, rect.bottom, rect.right)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                Rect(rect.top, rawHeight - rect.right, rect.bottom, rawHeight - rect.left)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                Rect(rawWidth - rect.bottom, rawHeight - rect.right, rawWidth - rect.top, rawHeight - rect.left)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                Rect(rawWidth - rect.bottom, rect.left, rawWidth - rect.top, rect.right)
            }

            else -> {
                Rect(rect)
            }
        }

    fun rawToOriented(
        rect: Rect,
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
    ): Rect =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                Rect(rawWidth - rect.right, rect.top, rawWidth - rect.left, rect.bottom)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                Rect(rawWidth - rect.right, rawHeight - rect.bottom, rawWidth - rect.left, rawHeight - rect.top)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                Rect(rect.left, rawHeight - rect.bottom, rect.right, rawHeight - rect.top)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                Rect(rect.top, rect.left, rect.bottom, rect.right)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                Rect(rawHeight - rect.bottom, rect.left, rawHeight - rect.top, rect.right)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                Rect(rawHeight - rect.bottom, rawWidth - rect.right, rawHeight - rect.top, rawWidth - rect.left)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                Rect(rect.top, rawWidth - rect.right, rect.bottom, rawWidth - rect.left)
            }

            else -> {
                Rect(rect)
            }
        }

    private fun matrix(orientation: Int): Matrix? =
        Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                    setScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    setRotate(180f)
                }

                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setScale(1f, -1f)
                }

                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    setRotate(90f)
                }

                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    setRotate(-90f)
                }

                else -> {
                    return null
                }
            }
        }
}
