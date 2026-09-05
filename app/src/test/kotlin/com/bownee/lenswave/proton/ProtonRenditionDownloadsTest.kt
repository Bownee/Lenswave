package com.bownee.lenswave.proton

import android.graphics.Bitmap
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProgressUpdate
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.FileThumbnail
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ThumbnailType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.UnknownHostException
import java.nio.channels.WritableByteChannel

/**
 * Drives the real downloader over a scripted SDK client: only the client, the rendition
 * stores and the diagnostics are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonRenditionDownloadsTest {
    private val cache = FakeMediaCache()
    private val client = FakeClientProvider()
    private val admission = ProtonPreviewAdmission()
    private val failures = mutableListOf<Pair<LenswaveOperation, Throwable>>()
    private val states = mutableListOf<String>()
    private val downloads =
        ProtonRenditionDownloads(
            client,
            cache,
            ProtonTransferCoordinator(),
            admission,
            { operation, error -> failures += operation to error },
            { _, state -> states += state },
        )

    @Test
    fun `thumbnails Proton does not have are deferred while previews are not allowed`() =
        runTest {
            admission.bind { false }
            client.thumbnails = { nodeUids -> nodeUids.map { nodeUid -> noThumbnail(nodeUid) } }
            val reports = mutableListOf<ThumbnailBatchResult>()

            val result = downloads.downloadThumbnails(USER, listOf("a", "b")) { reports += it }

            assertEquals(setOf("a", "b"), result.deferredNodeUids)
            assertTrue(result.failures.isEmpty())
            assertTrue(result.successfulNodeUids.isEmpty())
            // A deferred node is nobody's failure: no progress report settles it, and the
            // preview rendition was not asked for.
            assertTrue(reports.toString(), reports.all { report -> report.failures.isEmpty() })
            assertEquals(listOf(ThumbnailType.THUMBNAIL), client.enumerations.map { it.first }.distinct())
            assertTrue(cache.thumbnails.isEmpty())
        }

    @Test
    fun `the preview stands in for a missing thumbnail once previews are allowed`() =
        runTest {
            admission.bind { true }
            client.thumbnails = { nodeUids -> nodeUids.map { nodeUid -> noThumbnail(nodeUid) } }
            client.previews = { nodeUids -> nodeUids.map { nodeUid -> bytes(nodeUid) } }

            val result = downloads.downloadThumbnails(USER, listOf("a", "b")) {}

            assertEquals(setOf("a", "b"), result.successfulNodeUids)
            assertEquals(setOf("a", "b"), result.previewsStored)
            assertTrue(result.deferredNodeUids.isEmpty())
            assertTrue(result.failures.isEmpty())
            assertEquals(setOf("a", "b"), cache.thumbnails)
            assertEquals(setOf("a", "b"), cache.previews)
        }

    @Test
    fun `a thumbnail that is stored is a success reported as it lands`() =
        runTest {
            client.thumbnails = { nodeUids -> nodeUids.map { nodeUid -> bytes(nodeUid) } }
            val reports = mutableListOf<ThumbnailBatchResult>()

            val result = downloads.downloadThumbnails(USER, listOf("a")) { reports += it }

            assertEquals(setOf("a"), result.successfulNodeUids)
            assertEquals(listOf(setOf("a")), reports.map(ThumbnailBatchResult::successfulNodeUids))
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `a thumbnail the store refuses is an ordinary failure, whatever the store threw`() =
        runTest {
            admission.bind { true }
            cache.failThumbnailWrites = IOException("disk full")
            client.thumbnails = { nodeUids -> nodeUids.map { nodeUid -> bytes(nodeUid) } }
            client.previews = { nodeUids -> nodeUids.map { nodeUid -> noThumbnail(nodeUid) } }

            val result = downloads.downloadThumbnails(USER, listOf("a")) {}

            assertEquals(mapOf("a" to ThumbnailFailureKind.OTHER), result.failures)
        }

    @Test
    fun `a progress report that throws under the pass is not a lost connection`() =
        runTest {
            admission.bind { true }
            val nodeUids = ('a'..'e').map(Char::toString)
            client.thumbnails = { requested -> requested.map { nodeUid -> bytes(nodeUid) } }
            client.previews = { requested -> requested.map { nodeUid -> noThumbnail(nodeUid) } }
            var thrown = false

            val result =
                downloads.downloadThumbnails(USER, nodeUids) { report ->
                    // The first report of stored thumbnails fails once, as a queue write might.
                    if (!thrown && report.successfulNodeUids.isNotEmpty()) {
                        thrown = true
                        throw IOException("queue file")
                    }
                }

            assertTrue(thrown)
            assertEquals(setOf("a", "b", "c", "d"), result.successfulNodeUids)
            assertEquals(mapOf("e" to ThumbnailFailureKind.OTHER), result.failures)
            assertEquals(
                listOf(LenswaveOperation.THUMBNAIL_DOWNLOAD, LenswaveOperation.PREVIEW_DOWNLOAD),
                failures.map { it.first },
            )
        }

    @Test
    fun `a connection lost under the batch surfaces whole for the sync to classify`() =
        runTest {
            client.thumbnails = { throw UnknownHostException("api.proton.me") }

            val failed = runCatching { downloads.downloadThumbnails(USER, listOf("a", "b")) {} }

            // The SDK's flow fails the pass's scope; the sync backs the batch off as a network
            // failure (see ProtonRenditionSync) and the preview fallback is not tried over the
            // same dead network.
            assertTrue(failed.exceptionOrNull().toString(), failed.exceptionOrNull() is UnknownHostException)
            assertEquals(listOf(ThumbnailType.THUMBNAIL), client.enumerations.map { it.first })
        }

    @Test
    fun `a batch the SDK never answers is settled as a network failure without re-asks or the fallback`() =
        runTest {
            admission.bind { true }
            client.thumbnails = { awaitCancellation() }
            val reports = mutableListOf<ThumbnailBatchResult>()

            val result = downloads.downloadThumbnails(USER, listOf("a", "b")) { reports += it }

            assertTrue(result.stalled)
            assertEquals(
                mapOf("a" to ThumbnailFailureKind.TRANSIENT_NETWORK, "b" to ThumbnailFailureKind.TRANSIENT_NETWORK),
                result.failures,
            )
            assertTrue(result.successfulNodeUids.isEmpty())
            // One pass and one deadline: neither node was re-asked on its own, and the preview
            // rendition was not tried although previews were allowed.
            assertEquals(listOf(ThumbnailType.THUMBNAIL to listOf("a", "b")), client.enumerations)
            assertEquals(listOf("unanswered-2-of-2-deadline", "stalled-2-of-2"), states)
            assertTrue(reports.toString(), reports.isEmpty())
            assertEquals(ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS, currentTime)
        }

    @Test
    fun `a chunk left silent beside one that answered is slow nodes, re-asked on their own`() =
        runTest {
            val nodeUids = ('a'..'i').map(Char::toString)
            check(nodeUids.size == ProtonThumbnailDownloadPolicy.SDK_BATCH_SIZE + 1)
            var silentPasses = 0
            client.thumbnails = { requested ->
                // The one-node chunk of the batch pass stays silent; its re-ask answers.
                if (requested.size == 1 && silentPasses++ == 0) awaitCancellation()
                requested.map { nodeUid -> bytes(nodeUid) }
            }

            val result = downloads.downloadThumbnails(USER, nodeUids) {}

            assertFalse(result.stalled)
            assertEquals(nodeUids.toSet(), result.successfulNodeUids)
            assertTrue(result.failures.isEmpty())
            assertEquals(listOf(8, 1, 1), client.enumerations.map { (_, requested) -> requested.size })
        }

    @Test
    fun `a preview batch the SDK never answers is settled as a network failure without re-asks`() =
        runTest {
            admission.bind { true }
            client.previews = { awaitCancellation() }

            val result = downloads.downloadPreviews(USER, listOf("a", "b")) {}

            assertTrue(result.stalled)
            assertEquals(
                mapOf("a" to ThumbnailFailureKind.TRANSIENT_NETWORK, "b" to ThumbnailFailureKind.TRANSIENT_NETWORK),
                result.failures,
            )
            assertEquals(listOf(ThumbnailType.PREVIEW to listOf("a", "b")), client.enumerations)
            assertEquals(ProtonThumbnailDownloadPolicy.PREVIEW_FIRST_ANSWER_TIMEOUT_MILLIS, currentTime)
        }

    @Test
    fun `a stalled preview batch settles only the chunks it asked and defers the ones it did not`() =
        runTest {
            val nodeUids = ('a'..'i').map(Char::toString)
            check(nodeUids.size == ProtonThumbnailDownloadPolicy.SDK_BATCH_SIZE + 1)
            // The charger goes between the two chunks getting their permits: the first chunk
            // is asked and never answered, the second is never asked.
            var asks = 0
            admission.bind { asks++ == 0 }
            client.previews = { awaitCancellation() }

            val result = downloads.downloadPreviews(USER, nodeUids) {}

            assertTrue(result.stalled)
            assertEquals(nodeUids.take(8).associateWith { ThumbnailFailureKind.TRANSIENT_NETWORK }, result.failures)
            assertEquals(setOf("i"), result.deferredNodeUids)
            assertEquals(listOf(ThumbnailType.PREVIEW to nodeUids.take(8)), client.enumerations)
            assertEquals(listOf("unanswered-8-of-8-deadline", "stalled-8-of-8"), states)
        }

    @Test
    fun `a batch is stalled only when every one of its passes was`() {
        val silent = ThumbnailBatchResult(emptySet(), mapOf("a" to ThumbnailFailureKind.UNANSWERED), stalled = true)
        val answered = ThumbnailBatchResult(setOf("b"), emptyMap())

        assertTrue(ThumbnailBatchStallPolicy.isStalled(listOf(silent, silent)))
        assertFalse(ThumbnailBatchStallPolicy.isStalled(listOf(silent, answered)))
        assertFalse(ThumbnailBatchStallPolicy.isStalled(emptyList()))
        assertEquals(
            mapOf("a" to ThumbnailFailureKind.TRANSIENT_NETWORK),
            ThumbnailBatchStallPolicy.settleStalled(listOf("a", "b"), successful = setOf("b")),
        )
    }

    private fun noThumbnail(nodeUid: NodeUid): FileThumbnail =
        FileThumbnail(
            nodeUid,
            Result.failure(
                ProtonDriveSdkException("File thumbnail failure: This item has no image preview", null, null),
            ),
        )

    private fun bytes(nodeUid: NodeUid): FileThumbnail = FileThumbnail(nodeUid, Result.success(byteArrayOf(1, 2, 3)))

    /** Answers [ProtonPhotosClient.enumerateThumbnails] from [thumbnails] and [previews]; refuses everything else. */
    private class FakeClientProvider : ProtonPhotosClientProvider {
        var thumbnails: suspend (List<NodeUid>) -> List<FileThumbnail> = { error("no thumbnail pass expected") }
        var previews: suspend (List<NodeUid>) -> List<FileThumbnail> = { error("no preview pass expected") }
        val enumerations = mutableListOf<Pair<ThumbnailType, List<String>>>()

        override suspend fun get(userId: UserId): ProtonPhotosClient =
            Proxy.newProxyInstance(
                ProtonPhotosClient::class.java.classLoader,
                arrayOf(ProtonPhotosClient::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "enumerateThumbnails" -> {
                        @Suppress("UNCHECKED_CAST")
                        val nodeUids = arguments[0] as List<NodeUid>
                        val type = arguments[1] as ThumbnailType
                        enumerations += type to nodeUids.map { it.value }
                        val answers = if (type == ThumbnailType.PREVIEW) previews else thumbnails
                        flow { answers(nodeUids).forEach { answer -> emit(answer) } }
                    }

                    "toString" -> {
                        "FakeProtonPhotosClient"
                    }

                    "hashCode" -> {
                        0
                    }

                    else -> {
                        error("The SDK must not be asked for ${method.name}")
                    }
                }
            } as ProtonPhotosClient

        override suspend fun disconnect(userId: UserId) = error("no disconnect expected")

        override suspend fun downloadTo(
            userId: UserId,
            nodeUid: String,
            output: WritableByteChannel,
            onProgress: (ProgressUpdate) -> Unit,
        ) = error("no download expected")
    }

    /** The rendition stores as name sets; [failThumbnailWrites] makes the thumbnail store refuse. */
    private class FakeMediaCache : ProtonMediaCache {
        val thumbnails = mutableSetOf<String>()
        val previews = mutableSetOf<String>()
        var failThumbnailWrites: Throwable? = null

        override fun thumbnailExists(
            userId: String,
            nodeUid: String,
        ): Boolean = nodeUid in thumbnails

        override fun loadThumbnail(
            userId: String,
            nodeUid: String,
            isActive: () -> Boolean,
        ): Bitmap? = error("no thumbnail load expected")

        override fun peekThumbnail(
            userId: String,
            nodeUid: String,
        ): Bitmap? = null

        override fun writeThumbnail(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            failThumbnailWrites?.let { throw it }
            thumbnails += nodeUid
        }

        override fun removeThumbnail(
            userId: String,
            nodeUid: String,
        ) {
            thumbnails -= nodeUid
        }

        override fun thumbnailCount(userId: String): Int = thumbnails.size

        override fun readThumbnailBytes(
            userId: String,
            nodeUid: String,
        ): ByteArray? = null

        override fun previewExists(
            userId: String,
            nodeUid: String,
        ): Boolean = nodeUid in previews

        override fun writePreview(
            userId: String,
            nodeUid: String,
            bytes: ByteArray,
        ) {
            previews += nodeUid
        }

        override fun loadPreview(
            userId: String,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? = null

        override fun removePreview(
            userId: String,
            nodeUid: String,
        ) {
            previews -= nodeUid
        }

        override fun previewCount(userId: String): Int = previews.size

        override fun readOriginal(
            userId: String,
            nodeUid: String,
            shouldContinue: () -> Boolean,
        ): File? = null

        override fun createOriginalTarget(
            userId: String,
            nodeUid: String,
        ): ProtonOriginalTarget = error("no original expected")

        override fun commitOriginal(
            userId: String,
            nodeUid: String,
            download: ProtonOriginalTarget,
        ): ProtonOriginalCommit = error("no original expected")

        override fun onOriginalStored(
            userId: String,
            target: File,
        ) = Unit
    }

    private companion object {
        val USER = UserId("user")
    }
}
