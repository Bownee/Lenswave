package com.bownee.lenswave

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExifOrientationInstrumentedTest {
    @Test
    fun allEightOrientationsRoundTripAsymmetricPixels() {
        val orientations =
            intArrayOf(
                ExifInterface.ORIENTATION_NORMAL,
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270,
            )
        val inverses =
            intArrayOf(
                ExifInterface.ORIENTATION_NORMAL,
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_90,
            )

        for (index in orientations.indices) {
            val transformed = ExifOrientation.apply(asymmetricBitmap(), orientations[index], false)
            val restored = ExifOrientation.apply(transformed, inverses[index], false)
            try {
                assertEquals(2, restored.width)
                assertEquals(3, restored.height)
                for (y in 0 until restored.height) {
                    for (x in 0 until restored.width) assertEquals(colorAt(x, y), restored.getPixel(x, y))
                }
            } finally {
                restored.recycle()
            }
        }
    }

    private fun asymmetricBitmap(): Bitmap =
        Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) setPixel(x, y, colorAt(x, y))
            }
        }

    private fun colorAt(
        x: Int,
        y: Int,
    ): Int = 0xff000000.toInt() or ((x + 1) shl 16) or ((y + 1) shl 8) or (x + y + 1)
}
