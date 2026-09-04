package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeUid
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads full-size originals into the encrypted cache, one transfer per node at a time. */
@Singleton
internal class ProtonOriginalDownloads
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
