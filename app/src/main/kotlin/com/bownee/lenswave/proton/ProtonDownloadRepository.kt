package com.bownee.lenswave.proton

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import com.bownee.lenswave.LenswaveDiagnostics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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

    suspend fun findPhotoDuplicates(
        userId: UserId,
        name: String,
        generateSha1: suspend () -> ByteArray,
    ): List<String> {
        if (name.isBlank()) return emptyList()
        return clientProvider.get(userId).findPhotoDuplicates(name, generateSha1).map { it.value }
    }

    internal suspend fun downloadMissingThumbnails(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        nodeUids: Collection<String>,
        maxDownloads: Int? = null,
        onStored: (String) -> Unit,
        onProgress: () -> Unit,
    ) {
        try {
            downloadMissingThumbnailsUntrimmed(
                photosClient,
                userId,
                nodeUids,
                maxDownloads,
                onStored,
                onProgress,
            )
        } finally {
            cache.trimThumbnails(userId.id)
        }
    }

    private suspend fun downloadMissingThumbnailsUntrimmed(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        nodeUids: Collection<String>,
        maxDownloads: Int? = null,
        onStored: (String) -> Unit,
        onProgress: () -> Unit,
    ) {
        val missing = nodeUids.distinct()
            .filterNot { cache.thumbnailIsDecodable(userId.id, it) }
            .let { values -> maxDownloads?.let(values::take) ?: values }
            .toCollection(linkedSetOf())
        if (missing.isEmpty()) return
        val failures = mutableListOf<ThumbnailFailureKind>()

        var completedRequests = 0
        val throttledProgress = {
            completedRequests += 1
            if (completedRequests % PROGRESS_REQUEST_INTERVAL == 0) onProgress()
        }

        downloadThumbnailPass(
            photosClient,
            userId,
            missing,
            ThumbnailType.THUMBNAIL,
            MAX_THUMBNAILS_PER_BATCH,
            onStored,
            throttledProgress,
            failures,
        )
        downloadThumbnailPass(
            photosClient,
            userId,
            missing,
            ThumbnailType.THUMBNAIL,
            1,
            onStored,
            throttledProgress,
            failures,
        )
        downloadThumbnailPass(
            photosClient,
            userId,
            missing,
            ThumbnailType.PREVIEW,
            1,
            onStored,
            throttledProgress,
            failures,
        )
        onProgress()
        if (missing.isNotEmpty()) {
            throw ThumbnailDownloadException(
                missingCount = missing.size,
                kind = failures.maxByOrNull(ThumbnailFailureKind::priority) ?: ThumbnailFailureKind.UNKNOWN,
            )
        }
    }

    private suspend fun downloadThumbnailPass(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        missing: MutableSet<String>,
        type: ThumbnailType,
        batchSize: Int,
        onStored: (String) -> Unit,
        onProgress: () -> Unit,
        failures: MutableCollection<ThumbnailFailureKind>,
    ) {
        if (missing.isEmpty()) return
        missing.toList().chunked(batchSize).forEach { batch ->
            try {
                photosClient.enumerateThumbnails(batch.map(::NodeUid), type).collect { thumbnail ->
                    val nodeUid = thumbnail.uid.value
                    if (nodeUid !in missing) return@collect
                    val bytes = thumbnail.result.getOrElse { error ->
                        failures += ThumbnailFailureClassifier.classify(error)
                        return@collect
                    }
                    if (runCatching { cache.writeThumbnail(userId.id, nodeUid, bytes) }.isSuccess) {
                        missing.remove(nodeUid)
                        onStored(nodeUid)
                    } else {
                        failures += ThumbnailFailureKind.STORAGE
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure("thumbnail-download", error)
                failures += ThumbnailFailureClassifier.classify(error)
                // Isolated requests and the preview fallback can recover an incomplete batch.
            }
            onProgress()
        }
    }

    private companion object {
        const val MAX_THUMBNAILS_PER_BATCH = 30
        const val PROGRESS_REQUEST_INTERVAL = 8
    }
}

internal enum class ThumbnailFailureKind(val priority: Int) {
    UNKNOWN(0),
    NETWORK(1),
    NOT_FOUND(2),
    AUTHENTICATION(3),
    STORAGE(4),
}

internal class ThumbnailDownloadException(
    val missingCount: Int,
    val kind: ThumbnailFailureKind,
) : IOException("Proton thumbnail download incomplete ($kind, $missingCount missing)")

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
