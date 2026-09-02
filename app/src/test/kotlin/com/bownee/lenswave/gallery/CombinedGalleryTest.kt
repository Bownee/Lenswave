package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedGalleryTest {
    @Test
    fun incompleteDuplicateCheckCanRetryTheSameTimeline() {
        assertTrue(
            CombinedGallery.shouldRetry(
                CombinedMatchProgress(complete = true, errorMessage = "Temporary failure"),
            ),
        )
        assertFalse(CombinedGallery.shouldRetry(CombinedMatchProgress(complete = true)))
        assertFalse(
            CombinedGallery.shouldRetry(
                CombinedMatchProgress(complete = false, errorMessage = "Still running"),
            ),
        )
    }

    @Test
    fun exactMatchKeepsDeviceTileAndHidesEveryMatchingProtonCopy() {
        val device = devicePhoto("device:1", "IMG_0001.jpg")
        val protonOne = protonPhoto("proton-1", "IMG_0001.jpg")
        val protonTwo = protonPhoto("proton-2", "IMG_0001.jpg")

        val merged = CombinedGallery.merge(
            devicePhotos = listOf(device),
            protonPhotos = listOf(protonOne, protonTwo),
            matches = mapOf(device.stableId to listOf("proton-2", "proton-1")),
        )

        assertEquals(1, merged.size)
        assertEquals(PhotoSource.DEVICE, merged.single().source)
        assertEquals(device.uri, merged.single().uri)
        assertEquals(listOf("proton-1", "proton-2"), merged.single().protonBackingNodeUids)
        assertTrue(merged.single().isStoredInProton)
    }

    @Test
    fun sameFilenameRemainsVisibleTwiceWithoutAnExactMatch() {
        val device = devicePhoto("device:1", "IMG_0001.jpg")
        val proton = protonPhoto("proton-1", "IMG_0001.jpg")

        val merged = CombinedGallery.merge(listOf(device), listOf(proton), emptyMap())

        assertEquals(listOf(PhotoSource.DEVICE, PhotoSource.PROTON), merged.map(GalleryAsset::source))
    }

    @Test
    fun repeatedProtonNodeIsShownOnlyOnce() {
        val proton = protonPhoto("proton-1", "IMG_0001.jpg")

        val merged = CombinedGallery.merge(emptyList(), listOf(proton, proton), emptyMap())

        assertEquals(listOf("proton-1"), merged.map(GalleryAsset::protonNodeUid))
    }

    @Test
    fun repeatedDeviceMediaEntryIsShownOnlyOnce() {
        val device = devicePhoto("device:1", "IMG_0001.jpg")

        val merged = CombinedGallery.merge(listOf(device, device), emptyList(), emptyMap())

        assertEquals(listOf("device:1"), merged.map(GalleryAsset::stableId))
    }

    @Test
    fun captureTimeNarrowsRenamedProtonFallbackCandidates() {
        val device = devicePhoto("device:1", "RENAMED.jpg").copy(capturedAtEpochMillis = 12_345)
        val expected = ProtonGalleryPhoto("matching-time", captureTimeEpochSeconds = 12, hasThumbnail = true)
        val candidates = listOf(
            ProtonGalleryPhoto("before", captureTimeEpochSeconds = 11, hasThumbnail = true),
            expected,
            ProtonGalleryPhoto("after", captureTimeEpochSeconds = 13, hasThumbnail = true),
        )

        assertEquals(listOf(expected), CombinedGallery.captureTimeCandidates(device, candidates))
    }

    @Test
    fun staleCachedNodeDoesNotHideACurrentProtonPhoto() {
        val device = devicePhoto("device:1", "IMG_0001.jpg")
        val proton = protonPhoto("current-node", "IMG_0001.jpg")

        val merged = CombinedGallery.merge(
            listOf(device),
            listOf(proton),
            mapOf(device.stableId to listOf("removed-node")),
        )

        assertEquals(2, merged.size)
        assertTrue(merged.first().protonBackingNodeUids.isEmpty())
        assertEquals("current-node", merged.last().protonNodeUid)
    }

    @Test
    fun changedDeviceOrTimelineRequiresAnotherDuplicateCheck() {
        val photo = devicePhoto("device:1", "IMG_0001.jpg")
        val record = DevicePhotoMatchRecord(
            stableId = photo.stableId,
            displayName = photo.displayName,
            sizeBytes = photo.sizeBytes,
            modifiedAtEpochMillis = photo.modifiedAtEpochMillis,
            checkedTimelineFingerprint = "timeline-one",
            checkedAtEpochMillis = 10_000,
            matchStrategyVersion = CombinedGallery.MATCH_STRATEGY_VERSION,
        )

        assertFalse(CombinedGallery.shouldCheck(photo, record, "timeline-one", nowEpochMillis = 10_001))
        assertTrue(
            CombinedGallery.shouldCheck(
                devicePhoto("device:1", "IMG_0001.jpg", sizeBytes = photo.sizeBytes + 1),
                record,
                "timeline-one",
                nowEpochMillis = 10_001,
            ),
        )
        assertTrue(CombinedGallery.shouldCheck(photo, record, "timeline-two", nowEpochMillis = 10_001))
    }

    @Test
    fun negativeDuplicateResultsAreRecheckedAfterTheyBecomeStale() {
        val photo = devicePhoto("device:1", "IMG_0001.jpg")
        val record = DevicePhotoMatchRecord(
            stableId = photo.stableId,
            displayName = photo.displayName,
            sizeBytes = photo.sizeBytes,
            modifiedAtEpochMillis = photo.modifiedAtEpochMillis,
            checkedTimelineFingerprint = "timeline",
            checkedAtEpochMillis = 1,
            matchStrategyVersion = CombinedGallery.MATCH_STRATEGY_VERSION,
        )

        assertFalse(CombinedGallery.shouldCheck(photo, record, "timeline", nowEpochMillis = 2))
        assertTrue(CombinedGallery.shouldCheck(photo, record, "timeline", nowEpochMillis = 15 * 60 * 1_000L + 1))
    }

    @Test
    fun negativeResultsFromAnOlderMatchingStrategyAreRecheckedImmediately() {
        val photo = devicePhoto("device:1", "IMG_0001.jpg")
        val record = DevicePhotoMatchRecord(
            stableId = photo.stableId,
            displayName = photo.displayName,
            sizeBytes = photo.sizeBytes,
            modifiedAtEpochMillis = photo.modifiedAtEpochMillis,
            checkedTimelineFingerprint = "timeline",
            checkedAtEpochMillis = 10_000,
            matchStrategyVersion = CombinedGallery.MATCH_STRATEGY_VERSION - 1,
        )

        assertTrue(CombinedGallery.shouldCheck(photo, record, "timeline", nowEpochMillis = 10_001))
    }

    @Test
    fun positiveDuplicateResultsRemainStable() {
        val photo = devicePhoto("device:1", "IMG_0001.jpg")
        val record = DevicePhotoMatchRecord(
            stableId = photo.stableId,
            displayName = photo.displayName,
            sizeBytes = photo.sizeBytes,
            modifiedAtEpochMillis = photo.modifiedAtEpochMillis,
            checkedTimelineFingerprint = "timeline",
            checkedAtEpochMillis = 1,
            matchStrategyVersion = CombinedGallery.MATCH_STRATEGY_VERSION,
            protonNodeUids = listOf("proton-node"),
        )

        assertFalse(
            CombinedGallery.shouldCheck(
                photo,
                record,
                "timeline",
                nowEpochMillis = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun timelineFingerprintDoesNotDependOnEnumerationOrder() {
        assertEquals(
            CombinedGallery.timelineFingerprint(listOf("one", "two", "three")),
            CombinedGallery.timelineFingerprint(listOf("three", "one", "two")),
        )
    }

    private fun devicePhoto(id: String, name: String, sizeBytes: Long = 4_096) = GalleryAsset.device(
        stableId = id,
        capturedAtEpochMillis = 1_000,
        displayName = name,
        uri = "content://media/$id",
        collection = DeviceCollection.CAMERA,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = 2_000,
    )

    private fun protonPhoto(nodeUid: String, name: String) = GalleryAsset.proton(
        stableId = "proton:$nodeUid",
        capturedAtEpochMillis = 1_000,
        displayName = name,
        nodeUid = nodeUid,
        hasThumbnail = true,
    )
}
