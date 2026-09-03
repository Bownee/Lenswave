package com.bownee.lenswave.viewer

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.gallery.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRequestInstrumentedTest {
    @Test fun requestRoundTripsThroughAnIntent() {
        val request = PhotoRequest(
            stableId = "proton:1",
            nodeUid = "node",
            userId = "user",
            capturedAt = 34L,
            displayName = "cloud.jpg",
            isTrashed = true,
        )

        assertEquals(request, PhotoRequest.from(request.writeTo(Intent())))
    }

    @Test fun videoAndFavoriteStateSurviveIntentAndNavigationSerialization() {
        val request = PhotoRequest(
            stableId = "proton:video",
            nodeUid = "volume~video",
            userId = "user",
            capturedAt = 56L,
            displayName = "clip.mp4",
            isTrashed = false,
            mediaKind = MediaKind.VIDEO,
            isFavorite = true,
        )

        assertEquals(request, PhotoRequest.from(request.writeTo(Intent())))
        assertEquals(request, PhotoRequest.navigationFrom(Intent().apply {
            putParcelableArrayListExtra(
                PhotoViewerActivity.EXTRA_NAVIGATION,
                arrayListOf(request.toBundle()),
            )
        }).single())
    }
}
