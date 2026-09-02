package com.bownee.lenswave.proton

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import com.bownee.lenswave.LenswaveDiagnostics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ThumbnailType

@Singleton
internal class ProtonDownloadRepository @Inject constructor(
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonMediaCache,
) {
    suspend fun downloadOriginal(userId: UserId, nodeUid: String): File {
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
            LenswaveDiagnostics.reportFailure("original-download", error)
            temporary.delete()
            throw error
        }
    }

    suspend fun getOriginalFileName(userId: UserId, nodeUid: String): String? {
        return try {
            clientProvider.get(userId).getNode(NodeUid(nodeUid))?.originalFileName()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("original-name-load", error)
            null
        }
    }

    fun readThumbnail(userId: UserId, nodeUid: String): ByteArray? =
        cache.readThumbnail(userId.id, nodeUid)

    internal suspend fun downloadThumbnails(
        userId: UserId,
        nodeUids: Collection<String>,
    ): ThumbnailBatchResult {
        val requested = nodeUids.distinct()
        val successful = requested.filterTo(mutableSetOf()) { nodeUid ->
            cache.thumbnailIsDecodable(userId.id, nodeUid)
        }
        val pending = requested.filterNot { nodeUid -> nodeUid in successful }
        if (pending.isEmpty()) return ThumbnailBatchResult(successful, emptyMap())

        val photosClient = clientProvider.get(userId)
        val failures = mutableMapOf<String, ThumbnailFailureKind>()
        downloadChunks(photosClient, userId, pending).forEach { result ->
            successful += result.successfulNodeUids
            failures += result.failures
        }
        return ThumbnailBatchResult(successful, failures)
    }

    internal fun removeThumbnail(userId: UserId, nodeUid: String) {
        cache.removeThumbnail(userId.id, nodeUid)
    }

    suspend fun findPhotoDuplicates(
        userId: UserId,
        name: String,
        generateSha1: suspend () -> ByteArray,
    ): List<String> {
        if (name.isBlank()) return emptyList()
        return clientProvider.get(userId).findPhotoDuplicates(name, generateSha1).map { it.value }
    }

    private suspend fun downloadChunks(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        nodeUids: List<String>,
    ): List<ThumbnailBatchResult> = coroutineScope {
        val results = mutableListOf<ThumbnailBatchResult>()
        ProtonThumbnailDownloadPolicy.concurrentWindows(nodeUids).forEach { concurrentChunks ->
                results += concurrentChunks.map { chunk ->
                    async { downloadChunk(photosClient, userId, chunk) }
                }.awaitAll()
        }
        results
    }

    private suspend fun downloadChunk(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        nodeUids: List<String>,
    ): ThumbnailBatchResult {
        val successful = mutableSetOf<String>()
        val failures = mutableMapOf<String, ThumbnailFailureKind>()
        downloadThumbnailPass(
            photosClient,
            userId,
            nodeUids,
            ThumbnailType.THUMBNAIL,
            successful,
            failures,
        )
        val missing = nodeUids.filterNot { nodeUid -> nodeUid in successful }
        if (missing.isNotEmpty()) {
            downloadThumbnailPass(
                photosClient,
                userId,
                missing,
                ThumbnailType.PREVIEW,
                successful,
                failures,
            )
        }
        nodeUids.filterNot { nodeUid -> nodeUid in successful }.forEach { nodeUid ->
            failures.putIfAbsent(nodeUid, ThumbnailFailureKind.UNKNOWN)
        }
        return ThumbnailBatchResult(successful, failures.filterKeys { nodeUid -> nodeUid !in successful })
    }

    private suspend fun downloadThumbnailPass(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        nodeUids: Collection<String>,
        type: ThumbnailType,
        successful: MutableSet<String>,
        failures: MutableMap<String, ThumbnailFailureKind>,
    ) {
        val requested = nodeUids.toSet()
        try {
            photosClient.enumerateThumbnails(nodeUids.map(::NodeUid), type).collect { thumbnail ->
                val nodeUid = thumbnail.uid.value
                if (nodeUid !in requested) return@collect
                val bytes = thumbnail.result.getOrElse { error ->
                    failures.record(nodeUid, ThumbnailFailureClassifier.classify(error))
                    return@collect
                }
                if (runCatching { cache.writeThumbnail(userId.id, nodeUid, bytes) }.isSuccess) {
                    successful += nodeUid
                    failures.remove(nodeUid)
                } else {
                    failures.record(nodeUid, ThumbnailFailureKind.STORAGE)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("thumbnail-download", error)
            val kind = ThumbnailFailureClassifier.classify(error)
            requested.filterNot { nodeUid -> nodeUid in successful }
                .forEach { nodeUid -> failures.record(nodeUid, kind) }
        }
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
)

internal object ProtonThumbnailDownloadPolicy {
    const val SDK_BATCH_SIZE = 16
    const val MAX_CONCURRENT_BATCHES = 2
    const val BACKGROUND_CLAIM_SIZE = SDK_BATCH_SIZE * MAX_CONCURRENT_BATCHES
    const val VISIBLE_CLAIM_SIZE = 12

    fun <T> concurrentWindows(values: List<T>): List<List<List<T>>> =
        values.chunked(SDK_BATCH_SIZE).chunked(MAX_CONCURRENT_BATCHES)
}

internal enum class ThumbnailFailureKind(val priority: Int) {
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
