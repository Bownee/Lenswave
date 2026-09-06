package com.bownee.lenswave.gallery

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bownee.lenswave.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryThumbnailStateInstrumentedTest {
    @Test
    fun missingVideoKeepsSpinnerAcrossRepeatedBinds() =
        withGallery {
            for (available in listOf(false, true)) {
                val video = asset("video-$available", MediaKind.VIDEO, available)
                repeat(3) { assertLoading(bind(video)) }
            }
        }

    @Test
    fun missingPhotoKeepsSpinnerAcrossRepeatedBinds() =
        withGallery {
            val photo = asset("photo", MediaKind.IMAGE)
            repeat(3) { assertLoading(bind(photo)) }
        }

    @Test
    fun endingFastScrollKeepsMissingVideoInLoadingState() =
        withGallery {
            val video = asset("video", MediaKind.VIDEO)
            val cell = bind(video)
            adapter.setFastScrolling(true)
            bind(video)
            adapter.setFastScrolling(false)
            assertLoading(cell)
        }

    @Test
    fun loadedVideoKeepsItsThumbnailAndBadgeWhenReboundAfterCacheEviction() =
        withGallery {
            val video = asset("video", MediaKind.VIDEO, available = true)
            images.decoded[video.nodeUid] = bitmap
            val cell = bind(video)
            assertLoadedVideo(cell, bitmap)

            images.decoded.clear()
            bind(video)
            assertLoadedVideo(cell, bitmap)
            adapter.setFastScrolling(true)
            bind(video)
            adapter.setFastScrolling(false)
            assertLoadedVideo(cell, bitmap)

            adapter.clearThumbnails()
            assertLoading(cell)
            bind(video)
            assertLoading(cell)
        }

    @Test
    fun recycledVideoCellWaitsForItsOwnThumbnailAndIgnoresStaleDelivery() =
        withGallery {
            val first = asset("first", MediaKind.VIDEO, available = true)
            val second = asset("second", MediaKind.VIDEO, available = true)
            images.decoded[first.nodeUid] = bitmap
            val cell = bind(first)
            assertLoadedVideo(cell, bitmap)

            assertSame(cell, bind(second))
            assertLoading(cell)
            cell.thumbnailTarget.onThumbnail(first.stableId, bitmap)
            assertLoading(cell)
            cell.thumbnailTarget.onThumbnail(second.stableId, bitmap)
            assertLoadedVideo(cell, bitmap)

            val photo = asset("photo", MediaKind.IMAGE, available = true)
            images.decoded[photo.nodeUid] = bitmap
            bind(photo)
            assertSame(bitmap, (cell.image.drawable as BitmapDrawable).bitmap)
            assertEquals(View.GONE, cell.videoBadge.visibility)
            assertEquals(View.GONE, cell.loading.visibility)
        }

    private fun assertLoading(cell: PhotoCell) {
        assertNull((cell.image.drawable as? BitmapDrawable)?.bitmap)
        assertEquals("A missing thumbnail must keep its spinner", View.VISIBLE, cell.loading.visibility)
        assertEquals("A missing thumbnail must not show a play badge", View.GONE, cell.videoBadge.visibility)
    }

    private fun assertLoadedVideo(
        cell: PhotoCell,
        bitmap: Bitmap,
    ) {
        assertSame(bitmap, (cell.image.drawable as? BitmapDrawable)?.bitmap)
        assertEquals(View.GONE, cell.loading.visibility)
        assertEquals(View.VISIBLE, cell.videoBadge.visibility)
    }

    private fun asset(
        id: String,
        kind: MediaKind,
        available: Boolean = false,
    ) = GalleryAsset(
        stableId = id,
        capturedAtEpochMillis = 1L,
        nodeUid = id,
        hasThumbnail = available,
        mediaKind = kind,
    )

    private fun withGallery(test: GalleryFixture.() -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = GalleryFixture()
            try {
                fixture.test()
            } finally {
                fixture.scope.cancel()
            }
        }
    }

    private class GalleryFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val images = FakeImages()
        val bitmap: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        private val context =
            ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Lenswave)
        private val parent = FrameLayout(context)
        private var row: View? = null
        val adapter =
            GalleryListAdapter(
                context = context,
                thumbnailLoader = GalleryThumbnailLoader(scope, images, { UserId("thumbnail-test") }),
                onPhotoClicked = { _, _ -> },
                onAlbumClicked = {},
                onLibraryAction = {},
                onSelectionChanged = {},
            )

        fun bind(asset: GalleryAsset): PhotoCell {
            adapter.submitRows(GalleryRowSet.of(listOf(GalleryRow.Photos(listOf(asset)))))
            val boundRow = adapter.getView(0, row, parent)
            if (row == null) parent.addView(boundRow)
            row = boundRow
            return (boundRow as ViewGroup).getChildAt(0) as PhotoCell
        }
    }

    private class FakeImages : GalleryThumbnailImages<Bitmap> {
        val decoded = mutableMapOf<String, Bitmap>()

        override fun peek(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? = decoded[nodeUid]

        override suspend fun load(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? = awaitCancellation()
    }
}
