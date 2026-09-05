package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonAlbum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryThumbnailLoaderTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val images = FakeImages()
    private var userId: UserId? = USER
    private val loader = GalleryThumbnailLoader(scope, images, { userId }, ioDispatcher = dispatcher)

    @After
    fun stopScope() {
        scope.cancel()
    }

    @Test
    fun aPhotoWithoutAThumbnailIsAnsweredAtOnceWithoutTouchingTheSource() =
        runTest(dispatcher) {
            val target = RecordingTarget()

            loader.load(photo("p1", hasThumbnail = false), target = target)
            runCurrent()

            assertEquals(listOf("p1=null"), target.deliveries)
            assertEquals(0, images.peeks)
            assertEquals(emptyList<String>(), images.started)
        }

    @Test
    fun aDecodedThumbnailIsBoundSynchronouslyFromThePeek() =
        runTest(dispatcher) {
            images.decoded["n1"] = "bitmap-1"
            val target = RecordingTarget()

            loader.load(photo("p1"), target = target)

            assertEquals("delivered before any dispatch", listOf("p1=bitmap-1"), target.deliveries)
            runCurrent()
            assertEquals(emptyList<String>(), images.started)
        }

    @Test
    fun aMissingThumbnailShowsAPlaceholderAndArrivesFromTheAsynchronousLoad() =
        runTest(dispatcher) {
            val target = RecordingTarget()

            loader.load(photo("p1"), target = target)
            assertEquals(listOf("p1=null"), target.deliveries)
            runCurrent()
            assertEquals(listOf("n1"), images.started)

            images.complete("n1", "bitmap-1")
            runCurrent()
            assertEquals(listOf("p1=null", "p1=bitmap-1"), target.deliveries)
        }

    @Test
    fun withoutASignedInUserOrWhileFastScrollingNothingIsLoaded() =
        runTest(dispatcher) {
            val target = RecordingTarget()
            userId = null
            loader.load(photo("p1"), target = target)
            runCurrent()
            assertEquals(listOf("p1=null"), target.deliveries)
            assertEquals(0, images.peeks)

            userId = USER
            loader.load(photo("p1"), allowSourceRead = false, target = target)
            runCurrent()
            assertEquals(listOf("p1=null", "p1=null"), target.deliveries)
            assertEquals(1, images.peeks)
            assertEquals(emptyList<String>(), images.started)
        }

    @Test
    fun twoCellsWaitingOnTheSamePhotoShareOneLoad() =
        runTest(dispatcher) {
            val first = RecordingTarget()
            val second = RecordingTarget()

            loader.load(photo("p1"), target = first)
            loader.load(photo("p1"), target = second)
            runCurrent()
            assertEquals(listOf("n1"), images.started)

            images.complete("n1", "bitmap-1")
            runCurrent()
            assertEquals(listOf("p1=null", "p1=bitmap-1"), first.deliveries)
            assertEquals(listOf("p1=null", "p1=bitmap-1"), second.deliveries)
        }

    @Test
    fun theLoadIsCancelledOnlyWhenTheLastInterestedCellLetsGo() =
        runTest(dispatcher) {
            val first = RecordingTarget()
            val second = RecordingTarget()
            loader.load(photo("p1"), target = first)
            loader.load(photo("p1"), target = second)
            runCurrent()

            loader.forget(first)
            runCurrent()
            assertFalse("another cell still waits", "n1" in images.cancelled)

            loader.forget(second)
            runCurrent()
            assertTrue("n1" in images.cancelled)
            images.complete("n1", "bitmap-1")
            runCurrent()
            assertEquals("a cancelled load delivers nothing", listOf("p1=null"), first.deliveries)
            assertEquals(listOf("p1=null"), second.deliveries)
        }

    @Test
    fun rebindingACellToThePhotoItAlreadyWaitsForDoesNotRestartTheLoad() =
        runTest(dispatcher) {
            val target = RecordingTarget()
            loader.load(photo("p1"), target = target)
            runCurrent()

            loader.load(photo("p1"), target = target)
            runCurrent()

            assertEquals(listOf("n1"), images.started)
            assertFalse("n1" in images.cancelled)
            images.complete("n1", "bitmap-1")
            runCurrent()
            assertEquals(listOf("p1=null", "p1=null", "p1=bitmap-1"), target.deliveries)
        }

    @Test
    fun rebindingACellToAnotherPhotoWithdrawsItsInterestInTheFirst() =
        runTest(dispatcher) {
            val target = RecordingTarget()
            loader.load(photo("p1"), target = target)
            runCurrent()

            loader.load(photo("p2"), target = target)
            runCurrent()

            assertTrue("nobody waits on the first photo any more", "n1" in images.cancelled)
            assertEquals(listOf("n1", "n2"), images.started)
            images.complete("n2", "bitmap-2")
            runCurrent()
            assertEquals(listOf("p1=null", "p2=null", "p2=bitmap-2"), target.deliveries)
        }

    @Test
    fun aCachedRebindForgetsThePendingLoad() =
        runTest(dispatcher) {
            val target = RecordingTarget()
            loader.load(photo("p1"), target = target)
            runCurrent()

            images.decoded["n2"] = "bitmap-2"
            loader.load(photo("p2"), target = target)
            runCurrent()

            assertTrue("n1" in images.cancelled)
            assertEquals(listOf("p1=null", "p2=bitmap-2"), target.deliveries)
        }

    @Test
    fun cancellingPendingLoadsDropsEveryJobAndAFreshLoadOfTheSameKeyDeliversOnlyToItsOwnCell() =
        runTest(dispatcher) {
            val stale = RecordingTarget()
            val fresh = RecordingTarget()
            loader.load(photo("p1"), target = stale)
            runCurrent()

            loader.cancelPendingLoads()
            runCurrent()
            assertTrue("n1" in images.cancelled)

            loader.load(photo("p1"), target = fresh)
            runCurrent()
            assertEquals("a second job for the same key was started", listOf("n1", "n1"), images.started)
            images.complete("n1", "bitmap-1")
            runCurrent()

            assertEquals(listOf("p1=null"), stale.deliveries)
            assertEquals(listOf("p1=null", "p1=bitmap-1"), fresh.deliveries)
        }

    @Test
    fun aFailedReadDeliversNoImage() =
        runTest(dispatcher) {
            val target = RecordingTarget()
            loader.load(photo("p1"), target = target)
            runCurrent()

            images.fail("n1", IllegalStateException("boom"))
            runCurrent()

            assertEquals(listOf("p1=null", "p1=null"), target.deliveries)
        }

    @Test
    fun anAlbumCoverIsKeyedByItsCoverPhoto() =
        runTest(dispatcher) {
            val bare = RecordingTarget()
            loader.load(album("a0", cover = null), target = bare)
            assertEquals(listOf("a0=null"), bare.deliveries)

            val noThumbnail = RecordingTarget()
            loader.load(album("a1", cover = "c1", hasCoverThumbnail = false), target = noThumbnail)
            assertEquals(listOf("a1=null"), noThumbnail.deliveries)

            images.decoded["c2"] = "cover-2"
            val cached = RecordingTarget()
            loader.load(album("a2", cover = "c2"), target = cached)
            assertEquals(listOf("a2=cover-2"), cached.deliveries)

            val loaded = RecordingTarget()
            val photoWithSameNode = RecordingTarget()
            loader.load(album("a3", cover = "c3"), target = loaded)
            loader.load(photo("p3", nodeUid = "c3"), target = photoWithSameNode)
            runCurrent()
            assertEquals(
                "an album cover and a photo are separate loads even for one node",
                listOf("c3", "c3"),
                images.started,
            )
            images.complete("c3", "cover-3")
            runCurrent()
            assertEquals(listOf("a3=null", "a3=cover-3"), loaded.deliveries)
            assertEquals(listOf("p3=null", "p3=cover-3"), photoWithSameNode.deliveries)
        }

    private fun photo(
        id: String,
        nodeUid: String = "n${id.drop(1)}",
        hasThumbnail: Boolean = true,
    ) = GalleryAsset(
        stableId = id,
        capturedAtEpochMillis = 1L,
        nodeUid = nodeUid,
        hasThumbnail = hasThumbnail,
    )

    private fun album(
        nodeUid: String,
        cover: String?,
        hasCoverThumbnail: Boolean = true,
    ) = ProtonAlbum(
        nodeUid = nodeUid,
        name = nodeUid,
        photoCount = 1,
        coverPhotoNodeUid = cover,
        createdAtEpochSeconds = 0,
        lastActivityEpochSeconds = 0,
        hasCoverThumbnail = hasCoverThumbnail,
        isShared = false,
    )

    private class RecordingTarget : GalleryThumbnailTarget<String> {
        val deliveries = mutableListOf<String>()

        override fun onThumbnail(
            tag: String,
            image: String?,
        ) {
            deliveries += "$tag=$image"
        }
    }

    /** Images are strings; a load parks until the test completes or fails it, and notes a cancellation. */
    private class FakeImages : GalleryThumbnailImages<String> {
        val decoded = mutableMapOf<String, String>()
        val started = mutableListOf<String>()
        val cancelled = mutableSetOf<String>()
        var peeks = 0
        private val pending = mutableMapOf<String, CompletableDeferred<String?>>()

        override fun peek(
            userId: UserId,
            nodeUid: String,
        ): String? {
            peeks++
            return decoded[nodeUid]
        }

        override suspend fun load(
            userId: UserId,
            nodeUid: String,
        ): String? {
            started += nodeUid
            val result = pending.getOrPut(nodeUid, ::CompletableDeferred)
            try {
                return result.await()
            } catch (error: CancellationException) {
                cancelled += nodeUid
                throw error
            }
        }

        fun complete(
            nodeUid: String,
            image: String,
        ) {
            pending.remove(nodeUid)?.complete(image)
        }

        fun fail(
            nodeUid: String,
            error: Throwable,
        ) {
            pending.remove(nodeUid)?.completeExceptionally(error)
        }
    }

    private companion object {
        val USER = UserId("user")
    }
}
