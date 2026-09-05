package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class GallerySpanPolicyTest {
    @Test
    fun aPortraitPhoneKeepsThreeColumnsAndWiderListsTakeWholeCells() {
        val density = 2.625f // 411 dp wide at 1080 px
        assertEquals(3, GallerySpanPolicy.columns(1080, density))
        assertEquals(
            "landscape 2340 px is 891 dp, seven cells would fit but six is the cap",
            6,
            GallerySpanPolicy.columns(2340, density),
        )
        assertEquals("a 600 dp tablet pane takes five", 5, GallerySpanPolicy.columns(1200, 2f))
        assertEquals("a cell is never narrower than the target", 4, GallerySpanPolicy.columns(599, 1f))
    }

    @Test
    fun anUnknownWidthOrDensityFallsBackToTheMinimum() {
        assertEquals(3, GallerySpanPolicy.columns(0, 2f))
        assertEquals(3, GallerySpanPolicy.columns(-5, 2f))
        assertEquals(3, GallerySpanPolicy.columns(1000, 0f))
        assertEquals("a narrow pane never drops below three", 3, GallerySpanPolicy.columns(100, 3f))
    }

    @Test
    fun theFirstVisiblePhotoIsTheRowsOwnOrTheOneUnderAHeader() {
        val rows = rows(columns = 3, photoCount = 7)
        // header, [0..2], [3..5], [6]
        assertEquals(0, GallerySpanPolicy.firstAssetIndexAt(rows, 0))
        assertEquals(0, GallerySpanPolicy.firstAssetIndexAt(rows, 1))
        assertEquals(3, GallerySpanPolicy.firstAssetIndexAt(rows, 2))
        assertEquals(6, GallerySpanPolicy.firstAssetIndexAt(rows, 3))
        assertEquals(
            "a list header view on top counts from the first row",
            0,
            GallerySpanPolicy.firstAssetIndexAt(rows, -1),
        )
        assertNull(GallerySpanPolicy.firstAssetIndexAt(rows, 4))
        assertNull(GallerySpanPolicy.firstAssetIndexAt(emptyList(), 0))
    }

    @Test
    fun aPhotoIsFoundInItsRowOrUnderItsHeaderWhenItOpensTheDay() {
        val rows = rows(columns = 4, photoCount = 7)
        // header, [0..3], [4..6]
        assertEquals("the day's first photo lands on the header above", 0, GallerySpanPolicy.positionOfAsset(rows, 0))
        assertEquals(1, GallerySpanPolicy.positionOfAsset(rows, 3))
        assertEquals(2, GallerySpanPolicy.positionOfAsset(rows, 4))
        assertEquals(2, GallerySpanPolicy.positionOfAsset(rows, 6))
        assertNull(GallerySpanPolicy.positionOfAsset(rows, 7))
    }

    @Test
    fun aSavedPositionIsRemappedOnlyWhenTheSpanChanged() {
        val threeWide = rows(columns = 3, photoCount = 9)
        val sixWide = rows(columns = 6, photoCount = 9)
        // Second row of the three-wide grid (photos 3..5), one header view before the rows.
        val saved =
            GalleryScrollPosition(
                firstVisiblePosition = 3,
                topOffset = -12,
                photoColumns = 3,
                firstVisibleAssetIndex = 3,
            )

        assertSame("same span, same position", saved, GallerySpanPolicy.restoredPosition(saved, threeWide, 3, 1))
        assertEquals(
            "photo 3 is on the six-wide grid's first row, under the header, aligned to the top",
            GalleryScrollPosition(
                firstVisiblePosition = 2,
                topOffset = 0,
                photoColumns = 6,
                firstVisibleAssetIndex = 3,
            ),
            GallerySpanPolicy.restoredPosition(saved, sixWide, 6, 1),
        )
    }

    @Test
    fun aPositionWithoutAPhotoOrFromAnOlderStateIsKeptAsIs() {
        val rows = rows(columns = 4, photoCount = 5)
        val legacy = GalleryScrollPosition(firstVisiblePosition = 7, topOffset = 3)
        assertSame(legacy, GallerySpanPolicy.restoredPosition(legacy, rows, 4, 1))

        val gone =
            GalleryScrollPosition(
                firstVisiblePosition = 7,
                topOffset = 3,
                photoColumns = 3,
                firstVisibleAssetIndex = 40,
            )
        assertSame("the photo is no longer listed", gone, GallerySpanPolicy.restoredPosition(gone, rows, 4, 1))
    }

    private fun rows(
        columns: Int,
        photoCount: Int,
    ): List<GalleryRow> =
        GalleryGrouping.createRows(
            List(photoCount) { index ->
                GalleryAsset(
                    stableId = "p$index",
                    nodeUid = "n$index",
                    displayName = "",
                    capturedAtEpochMillis = 1_700_000_000_000L,
                    hasThumbnail = true,
                )
            },
            ZoneOffset.UTC,
            Locale.US,
            columns = columns,
            unknownDateLabel = "-",
        )
}
