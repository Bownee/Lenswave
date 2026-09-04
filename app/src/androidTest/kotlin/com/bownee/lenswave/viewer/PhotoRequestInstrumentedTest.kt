package com.bownee.lenswave.viewer

import android.content.Intent
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.gallery.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRequestInstrumentedTest {
    @Test fun requestRoundTripsThroughAnIntent() {
        val request =
            PhotoRequest(
                stableId = "proton:1",
                nodeUid = "node",
                userId = "user",
                capturedAt = 34L,
                displayName = "cloud.jpg",
            )

        assertEquals(request, PhotoRequest.from(request.writeTo(Intent())))
    }

    @Test fun videoAndFavoriteStateSurviveIntentAndNavigationSerialization() {
        val request =
            PhotoRequest(
                stableId = "proton:video",
                nodeUid = "volume~video",
                userId = "user",
                capturedAt = 56L,
                displayName = "clip.mp4",
                mediaKind = MediaKind.VIDEO,
                isFavorite = true,
            )

        assertEquals(request, PhotoRequest.from(request.writeTo(Intent())))
        assertEquals(
            request,
            PhotoRequest
                .navigationFrom(
                    Intent().apply {
                        putParcelableArrayListExtra(
                            PhotoViewerActivity.EXTRA_NAVIGATION,
                            arrayListOf(request),
                        )
                    },
                ).single(),
        )
    }

    @Test fun requestRoundTripsThroughAParcel() {
        val request =
            PhotoRequest(
                stableId = "proton:2",
                nodeUid = "volume~node",
                userId = "user",
                capturedAt = 78L,
                displayName = "still.heic",
                mediaKind = MediaKind.IMAGE,
                isFavorite = true,
            )
        val parcel = Parcel.obtain()
        try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals(request, PhotoRequest.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }
}
