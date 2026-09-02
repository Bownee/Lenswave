package com.bownee.lenswave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageProcessorInstrumentedTest {
    @Test
    fun savedCopyIsPublishedAsReadableJpegAndCanBeDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff336699.toInt())
        var saved: Uri? = null
        try {
            saved = ImageProcessor.saveJpegCopy(context, bitmap)
            assertEquals("image/jpeg", context.contentResolver.getType(saved))
            context.contentResolver.openInputStream(saved).use { input ->
                assertNotNull(input)
                BitmapFactory.decodeStream(input).also {
                    assertNotNull(it)
                    it?.recycle()
                }
            }
        } finally {
            bitmap.recycle()
            saved?.let { context.contentResolver.delete(it, null, null) }
        }
    }
}
