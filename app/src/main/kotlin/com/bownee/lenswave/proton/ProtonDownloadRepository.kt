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

    internal suspend fun downloadThumbnail(userId: UserId, nodeUid: String) {
        if (cache.thumbnailIsDecodable(userId.id, nodeUid)) return
        val failures = mutableListOf<ThumbnailFailureKind>()
        val photosClient = clientProvider.get(userId)
        if (downloadThumbnailPass(photosClient, userId, nodeUid, ThumbnailType.THUMBNAIL, failures)) return
        if (downloadThumbnailPass(photosClient, userId, nodeUid, ThumbnailType.PREVIEW, failures)) return
        throw ThumbnailDownloadException(
            missingCount = 1,
            kind = failures.maxByOrNull(ThumbnailFailureKind::priority) ?: ThumbnailFailureKind.UNKNOWN,
        )
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

    private suspend fun downloadThumbnailPass(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        nodeUid: String,
        type: ThumbnailType,
        failures: MutableCollection<ThumbnailFailureKind>,
    ): Boolean = try {
        var stored = false
        photosClient.enumerateThumbnails(listOf(NodeUid(nodeUid)), type).collect { thumbnail ->
            if (thumbnail.uid.value != nodeUid) return@collect
            val bytes = thumbnail.result.getOrElse { error ->
                failures += ThumbnailFailureClassifier.classify(error)
                return@collect
            }
            if (runCatching { cache.writeThumbnail(userId.id, nodeUid, bytes) }.isSuccess) {
                stored = true
            } else {
                failures += ThumbnailFailureKind.STORAGE
            }
        }
        stored
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        LenswaveDiagnostics.reportFailure("thumbnail-download", error)
        failures += ThumbnailFailureClassifier.classify(error)
        false
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
