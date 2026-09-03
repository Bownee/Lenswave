package com.bownee.lenswave.proton

import android.graphics.Bitmap
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ThumbnailType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ProtonDownloadRepository
    @Inject
    constructor(
        private val clientProvider: ProtonPhotosClientProvider,
        private val cache: ProtonMediaCache,
        private val transferCoordinator: ProtonTransferCoordinator,
    ) {
        private val originalFileNames = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val originalDownloadMutexes = Array(ORIGINAL_DOWNLOAD_MUTEX_COUNT) { Mutex() }

        suspend fun downloadOriginal(
            userId: UserId,
            nodeUid: String,
        ): File =
            originalDownloadMutex(userId, nodeUid).withLock {
                transferCoordinator.withForegroundTransfer {
                    downloadOriginalInForeground(userId, nodeUid)
                }
            }

        internal suspend fun downloadOriginalProgressively(
            userId: UserId,
            nodeUid: String,
            onReady: suspend (ProtonOriginalStream) -> Unit,
        ): File =
            originalDownloadMutex(userId, nodeUid).withLock {
                transferCoordinator.withForegroundTransfer {
                    cache.readOriginal(userId.id, nodeUid)?.let { cached ->
                        val stream = ProtonOriginalStream(cached).apply { complete() }
                        onReady(stream)
                        return@withForegroundTransfer cached
                    }

                    val (temporary, target) = cache.createOriginalTarget(userId.id, nodeUid)
                    val stream = ProtonOriginalStream(temporary)
                    try {
                        onReady(stream)
                        FileOutputStream(temporary).use { output ->
                            val reportingOutput = ProgressWritableByteChannel(output.channel, stream::bytesWritten)
                            clientProvider.downloadTo(userId, nodeUid, reportingOutput) { progress ->
                                stream.updateProgress(progress.bytesCompleted, progress.bytesInTotal)
                            }
                        }
                        stream.complete()
                        runCatching {
                            cache.commitOriginal(userId.id, nodeUid, temporary, target).also {
                                cache.onOriginalStored(userId.id, target)
                            }
                        }.getOrElse { error ->
                            LenswaveDiagnostics.reportFailure(LenswaveOperation.ORIGINAL_CACHE_STORE, error)
                            temporary
                        }
                    } catch (error: CancellationException) {
                        stream.fail(error)
                        temporary.delete()
                        throw error
                    } catch (error: Throwable) {
                        stream.fail(error)
                        LenswaveDiagnostics.reportFailure(LenswaveOperation.ORIGINAL_DOWNLOAD, error)
                        temporary.delete()
                        throw error
                    }
                }
            }

        suspend fun prepareCachedOriginal(
            userId: UserId,
            nodeUid: String,
        ): File? = originalDownloadMutex(userId, nodeUid).withLock { cache.readOriginal(userId.id, nodeUid) }

        private suspend fun downloadOriginalInForeground(
            userId: UserId,
            nodeUid: String,
        ): File {
            cache.readOriginal(userId.id, nodeUid)?.let { return it }
            val (temporary, target) = cache.createOriginalTarget(userId.id, nodeUid)
            return try {
                FileOutputStream(temporary).use { output ->
                    clientProvider.downloadTo(userId, nodeUid, output.channel)
                }
                val materialized = cache.commitOriginal(userId.id, nodeUid, temporary, target)
                cache.onOriginalStored(userId.id, target)
                materialized
            } catch (error: CancellationException) {
                temporary.delete()
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.ORIGINAL_DOWNLOAD, error)
                temporary.delete()
                throw error
            }
        }

        private fun originalDownloadMutex(
            userId: UserId,
            nodeUid: String,
        ): Mutex {
            val hash = 31 * userId.id.hashCode() + nodeUid.hashCode()
            return originalDownloadMutexes[(hash and Int.MAX_VALUE) % originalDownloadMutexes.size]
        }

        /** Drops memoized file names so a disconnected account leaves nothing behind in memory. */
        fun forgetUser(userId: UserId) {
            val prefix = "${userId.id}:"
            originalFileNames.keys.removeAll { key -> key.startsWith(prefix) }
        }

        suspend fun getOriginalFileName(
            userId: UserId,
            nodeUid: String,
        ): String? {
            val key = "${userId.id}:$nodeUid"
            originalFileNames[key]?.let { return it }
            return try {
                clientProvider
                    .get(userId)
                    .getNode(NodeUid(nodeUid))
                    ?.originalFileName()
                    ?.also { name -> originalFileNames[key] = name }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.ORIGINAL_NAME_LOAD, error)
                null
            }
        }

        fun loadThumbnail(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? = cache.loadThumbnail(userId.id, nodeUid)

        fun peekThumbnail(
            userId: UserId,
            nodeUid: String,
        ): Bitmap? = cache.peekThumbnail(userId.id, nodeUid)

        internal suspend fun downloadThumbnails(
            userId: UserId,
            nodeUids: Collection<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            val requested = nodeUids.distinct()
            val successful =
                requested.filterTo(mutableSetOf()) { nodeUid ->
                    cache.loadThumbnail(userId.id, nodeUid) != null
                }
            if (successful.isNotEmpty()) {
                onProgress(ThumbnailBatchResult(successful.toSet(), emptyMap()))
            }
            val pending = requested.filterNot { nodeUid -> nodeUid in successful }
            if (pending.isEmpty()) return ThumbnailBatchResult(successful, emptyMap())

            val photosClient = clientProvider.get(userId)
            val failures = mutableMapOf<String, ThumbnailFailureKind>()
            downloadChunks(photosClient, userId, pending, onProgress).forEach { result ->
                successful += result.successfulNodeUids
                failures += result.failures
            }
            return ThumbnailBatchResult(successful, failures)
        }

        internal fun removeThumbnail(
            userId: UserId,
            nodeUid: String,
        ) {
            cache.removeThumbnail(userId.id, nodeUid)
        }

        internal fun storedThumbnailCount(userId: UserId): Int = cache.thumbnailCount(userId.id)

        private suspend fun downloadChunks(
            photosClient: ProtonPhotosClient,
            userId: UserId,
            nodeUids: List<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): List<ThumbnailBatchResult> =
            coroutineScope {
                val results = mutableListOf<ThumbnailBatchResult>()
                ProtonThumbnailDownloadPolicy.concurrentWindows(nodeUids).forEach { concurrentChunks ->
                    results +=
                        concurrentChunks
                            .map { chunk ->
                                async { downloadChunk(photosClient, userId, chunk, onProgress) }
                            }.awaitAll()
                }
                results
            }

        private suspend fun downloadChunk(
            photosClient: ProtonPhotosClient,
            userId: UserId,
            nodeUids: List<String>,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            val thumbnailResult =
                downloadThumbnailPass(
                    photosClient,
                    userId,
                    nodeUids,
                    ThumbnailType.THUMBNAIL,
                    onProgress,
                )
            val successful = thumbnailResult.successfulNodeUids.toMutableSet()
            val failures = thumbnailResult.failures.toMutableMap()
            val missing = nodeUids.filterNot(successful::contains)
            if (missing.isEmpty()) return ThumbnailBatchResult(successful, emptyMap())

            val previewResult =
                downloadThumbnailPass(
                    photosClient,
                    userId,
                    missing,
                    ThumbnailType.PREVIEW,
                    onProgress,
                )
            successful += previewResult.successfulNodeUids
            previewResult.failures.forEach { (nodeUid, kind) -> failures.record(nodeUid, kind) }
            nodeUids.filterNot { nodeUid -> nodeUid in successful }.forEach { nodeUid ->
                failures.putIfAbsent(nodeUid, ThumbnailFailureKind.UNKNOWN)
            }
            val finalFailures = failures.filterKeys { nodeUid -> nodeUid !in successful }
            if (finalFailures.isNotEmpty()) {
                onProgress(ThumbnailBatchResult(emptySet(), finalFailures))
            }
            return ThumbnailBatchResult(successful, finalFailures)
        }

        private suspend fun downloadThumbnailPass(
            photosClient: ProtonPhotosClient,
            userId: UserId,
            nodeUids: Collection<String>,
            type: ThumbnailType,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult =
            transferCoordinator.withBackgroundTransfer {
                downloadThumbnailPassWhenAllowed(photosClient, userId, nodeUids, type, onProgress)
            }

        private suspend fun downloadThumbnailPassWhenAllowed(
            photosClient: ProtonPhotosClient,
            userId: UserId,
            nodeUids: Collection<String>,
            type: ThumbnailType,
            onProgress: suspend (ThumbnailBatchResult) -> Unit,
        ): ThumbnailBatchResult {
            val requested = nodeUids.toSet()
            val successful = mutableSetOf<String>()
            val unreportedSuccessful = mutableSetOf<String>()
            val failures = mutableMapOf<String, ThumbnailFailureKind>()

            suspend fun publishSuccessful() {
                if (unreportedSuccessful.isEmpty()) return
                onProgress(ThumbnailBatchResult(unreportedSuccessful.toSet(), emptyMap()))
                unreportedSuccessful.clear()
            }

            val completed =
                withTimeoutOrNull(ProtonThumbnailDownloadPolicy.SDK_PASS_TIMEOUT_MILLIS) {
                    try {
                        photosClient.enumerateThumbnails(nodeUids.map(::NodeUid), type).collect { thumbnail ->
                            val nodeUid = thumbnail.uid.value
                            if (nodeUid !in requested) return@collect
                            thumbnail.result.fold(
                                onSuccess = { bytes ->
                                    if (runCatching { cache.writeThumbnail(userId.id, nodeUid, bytes) }.isSuccess) {
                                        successful += nodeUid
                                        unreportedSuccessful += nodeUid
                                        failures.remove(nodeUid)
                                        if (unreportedSuccessful.size >=
                                            ProtonThumbnailDownloadPolicy.PROGRESS_BATCH_SIZE
                                        ) {
                                            publishSuccessful()
                                        }
                                    } else {
                                        failures.record(nodeUid, ThumbnailFailureKind.STORAGE)
                                    }
                                },
                                onFailure = { error ->
                                    failures.record(nodeUid, ThumbnailFailureClassifier.classify(error))
                                },
                            )
                            if (ThumbnailPassCompletionPolicy.hasResponseForEveryNode(
                                    requested,
                                    successful,
                                    failures.keys,
                                )
                            ) {
                                throw ThumbnailPassCompleteException()
                            }
                        }
                    } catch (_: ThumbnailPassCompleteException) {
                        // The SDK flow can remain open after delivering every result; no timeout is needed.
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        LenswaveDiagnostics.reportFailure(LenswaveOperation.THUMBNAIL_DOWNLOAD, error)
                        val kind = ThumbnailFailureClassifier.classify(error)
                        requested
                            .filterNot(successful::contains)
                            .forEach { nodeUid -> failures.record(nodeUid, kind) }
                    }
                    true
                }
            publishSuccessful()
            if (completed == null) {
                requested
                    .filterNot(successful::contains)
                    .forEach { nodeUid -> failures.record(nodeUid, ThumbnailFailureKind.NETWORK) }
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

        private companion object {
            const val ORIGINAL_DOWNLOAD_MUTEX_COUNT = 32
        }
    }

private class ProgressWritableByteChannel(
    private val delegate: WritableByteChannel,
    private val onBytesWritten: (Int) -> Unit,
) : WritableByteChannel {
    override fun write(source: ByteBuffer): Int = delegate.write(source).also(onBytesWritten)

    override fun isOpen(): Boolean = delegate.isOpen

    override fun close() = delegate.close()
}

internal data class ThumbnailBatchResult(
    val successfulNodeUids: Set<String>,
    val failures: Map<String, ThumbnailFailureKind>,
)

internal object ProtonThumbnailDownloadPolicy {
    const val SDK_BATCH_SIZE = 8
    const val MAX_CONCURRENT_BATCHES = 2
    const val BACKGROUND_CLAIM_SIZE = SDK_BATCH_SIZE * MAX_CONCURRENT_BATCHES
    const val PROGRESS_BATCH_SIZE = 4
    const val SDK_PASS_TIMEOUT_MILLIS = 15_000L

    fun <T> concurrentWindows(values: List<T>): List<List<List<T>>> =
        values.chunked(SDK_BATCH_SIZE).chunked(MAX_CONCURRENT_BATCHES)
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

private class ThumbnailPassCompleteException : CancellationException()

internal enum class ThumbnailFailureKind(
    val priority: Int,
) {
    UNKNOWN(0),
    NETWORK(1),
    NOT_FOUND(2),
    AUTHENTICATION(3),
    STORAGE(4),
}

internal object ThumbnailFailureClassifier {
    fun classify(error: Throwable): ThumbnailFailureKind {
        if (error is SocketTimeoutException) return ThumbnailFailureKind.NETWORK
        val type = error::class.java.simpleName.lowercase()
        return when {
            "unauthor" in type || "auth" in type -> ThumbnailFailureKind.AUTHENTICATION
            "notfound" in type || "not_found" in type -> ThumbnailFailureKind.NOT_FOUND
            error is IOException -> ThumbnailFailureKind.NETWORK
            else -> ThumbnailFailureKind.UNKNOWN
        }
    }
}
