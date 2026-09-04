package com.bownee.lenswave.proton

import android.graphics.Bitmap
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.FileThumbnail
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ThumbnailType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads grid thumbnails and screen-sized previews through the SDK in bounded batches and
 * stores them; the reads serve the gallery and the viewer straight from the encrypted stores.
 */
@Singleton
internal class ProtonRenditionDownloads
    @Inject
    constructor(
        private val clientProvider: ProtonPhotosClientProvider,
        private val cache: ProtonMediaCache,
        private val transferCoordinator: ProtonTransferCoordinator,
    ) : ProtonRenditionSource {
        fun loadThumbnail(
            userId: UserId,
            nodeUid: String,
            isActive: () -> Boolean = { true },
        ): Bitmap? = cache.loadThumbnail(userId.id, nodeUid, isActive)

        fun peekThumbnail(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? = cache.peekThumbnail(userId.id, nodeUid)

        override suspend fun downloadThumbnails(
            userId: UserId,
            nodeUids: Collection<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            val requested = nodeUids.distinct()
            val successful =
                requested.filterTo(mutableSetOf()) { nodeUid ->
                    // A file stat only, deliberately: decoding every stored thumbnail here would
                    // evict the gallery's own bitmaps and cost far more than it saves. The
                    // trade-off is that a corrupt stored file counts as available, fails to
                    // decode in the grid, and is queued again from there (see the gateway's
                    // invalidateThumbnail); that path is rare and self-correcting.
                    cache.thumbnailExists(userId.id, nodeUid)
                }
            if (successful.isNotEmpty()) {
                onProgress(ThumbnailBatchResult(successful.toSet(), emptyMap()))
            }
            val pending = requested.filterNot { nodeUid -> nodeUid in successful }
            if (pending.isEmpty()) return ThumbnailBatchResult(successful, emptyMap())

            val photosClient = clientProvider.get(userId)
            val failures = mutableMapOf<String, ThumbnailFailureKind>()

            suspend fun thumbnailPass(chunk: List<String>): ThumbnailBatchResult =
                downloadThumbnailPass(photosClient, userId, chunk, ThumbnailType.THUMBNAIL, onProgress)
            downloadChunks(pending, downloadChunk = ::thumbnailPass).forEach { result ->
                successful += result.successfulNodeUids
                failures += result.failures
            }
            // A node the SDK left unanswered in a batch is usually just slow, and answers when
            // asked on its own; asking now keeps it away from the preview fallback, which would
            // settle it on the wrong rendition.
            val unanswered = failures.filterValues { kind -> kind == ThumbnailFailureKind.UNANSWERED }.keys.toList()
            downloadChunks(
                unanswered,
                batchSize = 1,
                maxConcurrent = ProtonThumbnailDownloadPolicy.MAX_CONCURRENT_SINGLE_NODE_PASSES,
                downloadChunk = ::thumbnailPass,
            ).forEach { result ->
                successful += result.successfulNodeUids
                result.successfulNodeUids.forEach(failures::remove)
                failures += result.failures
            }
            val missing = pending.filterNot(successful::contains)
            if (missing.isEmpty()) return ThumbnailBatchResult(successful, emptyMap())

            val previewsStored = mutableSetOf<String>()
            downloadChunks(missing) { chunk ->
                downloadPreviewInPlaceOfThumbnail(photosClient, userId, chunk, onProgress, previewsStored)
            }.forEach { result ->
                successful += result.successfulNodeUids
                result.successfulNodeUids.forEach(failures::remove)
                result.failures.forEach { (nodeUid, previewKind) ->
                    failures[nodeUid] = ThumbnailFallbackFailurePolicy.settle(failures[nodeUid], previewKind)
                }
            }
            missing.filterNot(successful::contains).forEach { nodeUid ->
                failures.putIfAbsent(nodeUid, ThumbnailFailureKind.OTHER)
            }
            val finalFailures = failures.filterKeys { nodeUid -> nodeUid !in successful }
            if (previewsStored.isNotEmpty()) {
                onProgress(ThumbnailBatchResult(emptySet(), emptyMap(), previewsStored = previewsStored.toSet()))
            }
            if (finalFailures.isNotEmpty()) {
                onProgress(ThumbnailBatchResult(emptySet(), finalFailures))
            }
            return ThumbnailBatchResult(successful, finalFailures, previewsStored.toSet())
        }

        override fun removeThumbnail(
            userId: UserId,
            nodeUid: String,
        ) {
            cache.removeThumbnail(userId.id, nodeUid)
        }

        override fun storedThumbnailCount(userId: UserId): Int = cache.thumbnailCount(userId.id)

        fun loadPreview(
            userId: UserId,
            nodeUid: String,
            targetLongEdge: Int,
        ): Bitmap? = cache.loadPreview(userId.id, nodeUid, targetLongEdge)

        override fun storedPreviewCount(userId: UserId): Int = cache.previewCount(userId.id)

        /**
         * Downloads Proton's screen-sized preview rendition into the preview store. A single pass:
         * a photo without a preview on the server fails with [ThumbnailFailureKind.NOT_FOUND] and
         * the caller settles it permanently.
         */
        override suspend fun downloadPreviews(
            userId: UserId,
            nodeUids: Collection<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            val requested = nodeUids.distinct()
            val successful =
                requested.filterTo(mutableSetOf()) { nodeUid ->
                    cache.previewExists(userId.id, nodeUid)
                }
            if (successful.isNotEmpty()) {
                onProgress(ThumbnailBatchResult(successful.toSet(), emptyMap()))
            }
            val pending = requested.filterNot { nodeUid -> nodeUid in successful }
            if (pending.isEmpty()) return ThumbnailBatchResult(successful, emptyMap())

            val photosClient = clientProvider.get(userId)
            val failures = mutableMapOf<String, ThumbnailFailureKind>()

            suspend fun previewPass(chunk: List<String>): ThumbnailBatchResult =
                downloadThumbnailPass(
                    photosClient,
                    userId,
                    chunk,
                    ThumbnailType.PREVIEW,
                    onProgress,
                    timeoutMillis = ProtonThumbnailDownloadPolicy.PREVIEW_PASS_TIMEOUT_MILLIS,
                    store = { nodeUid, bytes -> cache.writePreview(userId.id, nodeUid, bytes) },
                )
            downloadChunks(pending, downloadChunk = ::previewPass).forEach { result ->
                successful += result.successfulNodeUids
                failures += result.failures
            }
            // Nodes the SDK skipped in a batch usually answer when asked on their own; asking now
            // keeps them out of the retry backoff.
            val unanswered = failures.filterValues { kind -> kind == ThumbnailFailureKind.UNANSWERED }.keys.toList()
            downloadChunks(
                unanswered,
                batchSize = 1,
                maxConcurrent = ProtonThumbnailDownloadPolicy.MAX_CONCURRENT_SINGLE_NODE_PASSES,
                downloadChunk = ::previewPass,
            ).forEach { result ->
                successful += result.successfulNodeUids
                result.successfulNodeUids.forEach(failures::remove)
                failures += result.failures
            }
            // A photo Proton has no preview for keeps its thumbnail as the preview: the viewer
            // shows that anyway, and a stored preview keeps the photo out of every later queue.
            val substituted =
                failures
                    .filterValues { kind -> kind == ThumbnailFailureKind.NOT_FOUND }
                    .keys
                    .filter { nodeUid ->
                        nodeUid !in successful &&
                            cache.readThumbnailBytes(userId.id, nodeUid)?.let { thumbnail ->
                                runCatching { cache.writePreview(userId.id, nodeUid, thumbnail) }.isSuccess
                            } == true
                    }.toSet()
            if (substituted.isNotEmpty()) {
                successful += substituted
                substituted.forEach(failures::remove)
                onProgress(ThumbnailBatchResult(substituted, emptyMap()))
            }
            // Failures are reported once, in the result; reporting them through onProgress as
            // well made the caller settle each one twice per pass, doubling the backoff steps.
            return ThumbnailBatchResult(successful, failures.filterKeys { nodeUid -> nodeUid !in successful })
        }

        /**
         * Runs the SDK passes for [nodeUids] at most [maxConcurrent] at a time. A permit frees as
         * soon as one pass ends, so a slow pass never holds the others back: the earlier windows
         * with a barrier per window left most of the concurrency idle whenever one node took
         * its whole deadline.
         */
        private suspend fun downloadChunks(
            nodeUids: List<String>,
            batchSize: Int = ProtonThumbnailDownloadPolicy.SDK_BATCH_SIZE,
            maxConcurrent: Int = ProtonThumbnailDownloadPolicy.MAX_CONCURRENT_BATCHES,
            downloadChunk: suspend (List<String>) -> ThumbnailBatchResult,
        ): List<ThumbnailBatchResult> =
            coroutineScope {
                val permits = Semaphore(maxConcurrent)
                ProtonThumbnailDownloadPolicy
                    .batches(nodeUids, batchSize)
                    .map { chunk -> async { permits.withPermit { downloadChunk(chunk) } } }
                    .awaitAll()
            }

        /**
         * The preview rendition stands in for a thumbnail Proton does not have. It is the same
         * bytes the preview queue would fetch later, so they go into the preview store as well
         * and the caller settles the node out of that queue; the mirror of the NOT_FOUND
         * substitution in downloadPreviews.
         */
        private suspend fun downloadPreviewInPlaceOfThumbnail(
            photosClient: ProtonPhotosClient,
            userId: UserId,
            nodeUids: List<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
            previewsStored: MutableSet<String>,
        ): ThumbnailBatchResult =
            downloadThumbnailPass(
                photosClient,
                userId,
                nodeUids,
                ThumbnailType.PREVIEW,
                onProgress,
                store = { nodeUid, bytes ->
                    cache.writeThumbnail(userId.id, nodeUid, bytes)
                    if (runCatching { cache.writePreview(userId.id, nodeUid, bytes) }.isSuccess) {
                        synchronized(previewsStored) { previewsStored += nodeUid }
                    }
                },
            )

        private suspend fun downloadThumbnailPass(
            photosClient: ProtonPhotosClient,
            userId: UserId,
            nodeUids: Collection<String>,
            type: ThumbnailType,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
            timeoutMillis: Long = ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS,
            store: (nodeUid: String, bytes: ByteArray) -> Unit = { nodeUid, bytes ->
                cache.writeThumbnail(userId.id, nodeUid, bytes)
            },
        ): ThumbnailBatchResult =
            transferCoordinator.withBackgroundTransfer {
                downloadThumbnailPassWhenAllowed(photosClient, nodeUids, type, onProgress, timeoutMillis, store)
            }

        private suspend fun downloadThumbnailPassWhenAllowed(
            photosClient: ProtonPhotosClient,
            nodeUids: Collection<String>,
            type: ThumbnailType,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
            timeoutMillis: Long,
            store: (nodeUid: String, bytes: ByteArray) -> Unit,
        ): ThumbnailBatchResult {
            val requested = nodeUids.toSet()
            val successful = mutableSetOf<String>()
            val unreportedSuccessful = mutableSetOf<String>()
            val failures = mutableMapOf<String, ThumbnailFailureKind>()
            val reportedKinds = mutableSetOf<ThumbnailFailureKind>()
            val operation =
                if (type == ThumbnailType.PREVIEW) {
                    LenswaveOperation.PREVIEW_DOWNLOAD
                } else {
                    LenswaveOperation.THUMBNAIL_DOWNLOAD
                }

            suspend fun publishSuccessful() {
                if (unreportedSuccessful.isEmpty()) return
                onProgress(ThumbnailBatchResult(unreportedSuccessful.toSet(), emptyMap()))
                unreportedSuccessful.clear()
            }

            /** Records one SDK answer; true once every requested node has one. */
            suspend fun handle(thumbnail: FileThumbnail): Boolean {
                val nodeUid = thumbnail.uid.value
                if (nodeUid !in requested) return false
                thumbnail.result.fold(
                    onSuccess = { bytes ->
                        if (runCatching { store(nodeUid, bytes) }.isSuccess) {
                            successful += nodeUid
                            unreportedSuccessful += nodeUid
                            failures.remove(nodeUid)
                            if (unreportedSuccessful.size >= ProtonThumbnailDownloadPolicy.PROGRESS_BATCH_SIZE) {
                                publishSuccessful()
                            }
                        } else {
                            failures.record(nodeUid, ThumbnailFailureKind.OTHER)
                        }
                    },
                    onFailure = { error ->
                        val kind = ThumbnailFailureClassifier.classify(error)
                        // One sample per failure kind and pass keeps the log readable
                        // while still showing why each rendition is refused.
                        if (reportedKinds.add(kind)) {
                            LenswaveDiagnostics.reportFailure(operation, error)
                        }
                        failures.record(nodeUid, kind)
                    },
                )
                return ThumbnailPassCompletionPolicy.hasResponseForEveryNode(requested, successful, failures.keys)
            }

            var wentQuiet = false
            var answered = false
            val completed =
                withTimeoutOrNull(timeoutMillis) {
                    coroutineScope {
                        val answers = photosClient.enumerateThumbnails(nodeUids.map(::NodeUid), type).produceIn(this)
                        try {
                            while (true) {
                                // The SDK sometimes answers only part of a batch and then stays
                                // silent; a quiet flow ends the pass well before the deadline.
                                // Before the first answer the SDK is still setting the batch up,
                                // which can take longer than the idle window under load, so
                                // only the deadline applies until then.
                                val waitMillis =
                                    ProtonThumbnailDownloadPolicy.answerWaitMillis(type, answered, timeoutMillis)
                                val next = withTimeoutOrNull(waitMillis) { answers.receiveCatching() }
                                if (next == null) {
                                    wentQuiet = true
                                    break
                                }
                                answered = true
                                val thumbnail = next.getOrNull()
                                if (thumbnail == null) {
                                    next.exceptionOrNull()?.let { error ->
                                        if (error !is CancellationException) throw error
                                    }
                                    break
                                }
                                if (handle(thumbnail)) break
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            LenswaveDiagnostics.reportFailure(LenswaveOperation.THUMBNAIL_DOWNLOAD, error)
                            val kind = ThumbnailFailureClassifier.classify(error)
                            requested
                                .filterNot(successful::contains)
                                .forEach { nodeUid -> failures.record(nodeUid, kind) }
                        } finally {
                            answers.cancel()
                        }
                    }
                    true
                }
            publishSuccessful()
            val unanswered = requested.filter { nodeUid -> nodeUid !in successful && nodeUid !in failures }
            if (unanswered.isNotEmpty()) {
                val reason =
                    if (completed == null || (wentQuiet && !answered)) {
                        "deadline"
                    } else if (wentQuiet) {
                        "quiet"
                    } else {
                        "closed"
                    }
                LenswaveDiagnostics.reportState(
                    operation,
                    "unanswered-${unanswered.size}-of-${requested.size}-$reason",
                    1,
                    1,
                )
                unanswered.forEach { nodeUid -> failures.record(nodeUid, ThumbnailFailureKind.UNANSWERED) }
            }
            return ThumbnailBatchResult(successful, failures.filterKeys { nodeUid -> nodeUid !in successful })
        }

        private fun MutableMap<String, ThumbnailFailureKind>.record(
            nodeUid: String,
            kind: ThumbnailFailureKind,
        ) {
            val previous = this[nodeUid]
            if (previous == null || kind.priority > previous.priority) this[nodeUid] = kind
        }
    }

internal data class ThumbnailBatchResult(
    val successfulNodeUids: Set<String>,
    val failures: Map<String, ThumbnailFailureKind>,
    /** Nodes whose preview rendition served as the thumbnail and was stored as a preview too. */
    val previewsStored: Set<String> = emptySet(),
)

internal object ProtonThumbnailDownloadPolicy {
    const val SDK_BATCH_SIZE = 8
    const val MAX_CONCURRENT_BATCHES = 2
    const val BACKGROUND_CLAIM_SIZE = SDK_BATCH_SIZE * MAX_CONCURRENT_BATCHES
    const val PROGRESS_BATCH_SIZE = 4
    const val SDK_PASS_TIMEOUT_MILLIS = 15_000L

    /** Previews are a few hundred kilobytes each, so one pass of [SDK_BATCH_SIZE] gets longer. */
    const val PREVIEW_PASS_TIMEOUT_MILLIS = 90_000L

    /** A pass ends once the SDK has said nothing for this long; the deadlines above are the cap. */
    const val SDK_IDLE_TIMEOUT_MILLIS = 6_000L
    const val PREVIEW_IDLE_TIMEOUT_MILLIS = 15_000L

    fun idleTimeoutMillis(type: ThumbnailType): Long =
        if (type == ThumbnailType.PREVIEW) PREVIEW_IDLE_TIMEOUT_MILLIS else SDK_IDLE_TIMEOUT_MILLIS

    /**
     * A preview batch the SDK never answers gives up here rather than at the pass deadline,
     * which is sized for the transfer once answers are flowing.
     */
    const val PREVIEW_FIRST_ANSWER_TIMEOUT_MILLIS = 30_000L

    /**
     * How long a pass waits for the next SDK answer. The idle window only starts once the SDK
     * has answered at least once: before that it is still fetching the batch's metadata and
     * keys, and giving up on a slow but working batch threw the whole batch away.
     */
    fun answerWaitMillis(
        type: ThumbnailType,
        answered: Boolean,
        passTimeoutMillis: Long,
    ): Long =
        when {
            answered -> idleTimeoutMillis(type)
            type == ThumbnailType.PREVIEW -> minOf(PREVIEW_FIRST_ANSWER_TIMEOUT_MILLIS, passTimeoutMillis)
            else -> passTimeoutMillis
        }

    /**
     * A single-node pass is one node's worth of transfer with the whole deadline to itself, so
     * more of them can run side by side than full batches; the re-ask of eight unanswered nodes
     * would otherwise cost four full deadlines of mostly idle time.
     */
    const val MAX_CONCURRENT_SINGLE_NODE_PASSES = 4

    fun <T> batches(
        values: List<T>,
        batchSize: Int = SDK_BATCH_SIZE,
    ): List<List<T>> {
        require(batchSize > 0) { "The batch size must be positive" }
        return values.chunked(batchSize)
    }
}

internal object ThumbnailPassCompletionPolicy {
    fun hasResponseForEveryNode(
        requestedNodeUids: Set<String>,
        successfulNodeUids: Set<String>,
        failedNodeUids: Set<String>,
    ): Boolean =
        requestedNodeUids.all { nodeUid ->
            nodeUid in successfulNodeUids || nodeUid in failedNodeUids
        }
}
