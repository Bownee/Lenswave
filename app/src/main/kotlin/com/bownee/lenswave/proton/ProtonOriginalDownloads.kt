package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeUid
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads full-size originals into the encrypted cache. Concurrent requests for the same node
 * share one transfer; requests for different nodes never wait on each other, and a cache hit is
 * answered before any coordination at all.
 */
@Singleton
internal class ProtonOriginalDownloads
    @Inject
    constructor(
        private val clientProvider: ProtonPhotosClientProvider,
        private val cache: ProtonMediaCache,
        private val transferCoordinator: ProtonTransferCoordinator,
    ) {
        /** Resolved names, including "Proton has none" so a missing name is asked for once. */
        private val originalFileNames = ConcurrentHashMap<String, ResolvedName>()
        private val fileNameLookups = ConcurrentHashMap<String, Deferred<String?>>()
        private val downloads = ConcurrentHashMap<String, ProtonSharedDownload<File>>()

        /**
         * Transfers run in their own scope so a caller that stops waiting (the viewer moved on)
         * does not tear down a download another caller still needs; the last waiter to leave
         * cancels it.
         */
        private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun downloadOriginal(
            userId: UserId,
            nodeUid: String,
        ): File =
            cache.readOriginal(userId.id, nodeUid)
                ?: shareDownload(userId, nodeUid) {
                    transferCoordinator.withForegroundTransfer {
                        downloadOriginalInForeground(userId, nodeUid)
                    }
                }

        internal suspend fun downloadOriginalProgressively(
            userId: UserId,
            nodeUid: String,
            onReady: suspend (ProtonOriginalStream) -> Unit,
        ): File {
            cache.readOriginal(userId.id, nodeUid)?.let { cached ->
                onReady(ProtonOriginalStream(cached).apply { complete() })
                return cached
            }
            var streamed = false
            val file =
                shareDownload(userId, nodeUid) {
                    transferCoordinator.withForegroundTransfer {
                        cache.readOriginal(userId.id, nodeUid)?.let { cached ->
                            return@withForegroundTransfer cached
                        }
                        streamed = true
                        downloadProgressivelyInForeground(userId, nodeUid, onReady)
                    }
                }
            // A transfer another caller started has no stream of ours; it is complete by now.
            if (!streamed) onReady(ProtonOriginalStream(file).apply { complete() })
            return file
        }

        private suspend fun downloadProgressivelyInForeground(
            userId: UserId,
            nodeUid: String,
            onReady: suspend (ProtonOriginalStream) -> Unit,
        ): File {
            val (temporary, target) = cache.createOriginalTarget(userId.id, nodeUid)
            val stream = ProtonOriginalStream(temporary)
            return try {
                onReady(stream)
                FileOutputStream(temporary).use { output ->
                    val reportingOutput = ProgressWritableByteChannel(output.channel, stream::bytesWritten)
                    clientProvider.downloadTo(userId, nodeUid, reportingOutput) { progress ->
                        stream.updateProgress(progress.bytesCompleted, progress.bytesInTotal)
                    }
                }
                stream.complete()
                // A cache that cannot keep the original is not a failed download: the plaintext
                // the viewer is already reading stays. A cancellation is one, and goes below.
                try {
                    cache.commitOriginal(userId.id, nodeUid, temporary, target).also {
                        cache.onOriginalStored(userId.id, target)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
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

        /**
         * The cached original, decrypted on demand; never waits on a transfer, of this node or any
         * other. [shouldContinue] lets a caller that lost interest stop the decrypt between chunks.
         */
        fun prepareCachedOriginal(
            userId: UserId,
            nodeUid: String,
            shouldContinue: () -> Boolean = { true },
        ): File? = cache.readOriginal(userId.id, nodeUid, shouldContinue)

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

        /**
         * Joins the transfer in flight for exactly this node or starts one. Waiters are counted so
         * the transfer is cancelled only once nobody is left waiting for it.
         */
        private suspend fun shareDownload(
            userId: UserId,
            nodeUid: String,
            transfer: suspend () -> File,
        ): File {
            val key = key(userId, nodeUid)
            while (true) {
                val shared =
                    downloads.computeIfAbsent(key) {
                        ProtonSharedDownload(transferScope.async(start = CoroutineStart.LAZY) { transfer() })
                            .also { created -> created.deferred.invokeOnCompletion { downloads.remove(key, created) } }
                    }
                if (!shared.tryJoin()) {
                    // The previous waiters all left and took the transfer down just as this one
                    // arrived; a fresh transfer replaces it.
                    downloads.remove(key, shared)
                    continue
                }
                shared.deferred.start()
                return shared.await()
            }
        }

        /**
         * Cancels the account's transfers and name lookups and drops what it memoized, and only
         * returns once they have stopped: a transfer running on after the disconnect would commit
         * into the wiped user's directory and recreate it together with its data key.
         */
        suspend fun forgetUser(userId: UserId) {
            val prefix = "${userId.id}:"
            val transfers = downloads.filterKeys { key -> key.startsWith(prefix) }
            transfers.forEach { (key, shared) -> downloads.remove(key, shared) }
            val lookups = fileNameLookups.filterKeys { key -> key.startsWith(prefix) }
            lookups.forEach { (key, lookup) ->
                fileNameLookups.remove(key, lookup)
                lookup.cancel()
            }
            transfers.values.forEach { shared -> shared.forget() }
            lookups.values.forEach { lookup -> lookup.join() }
            originalFileNames.keys.removeAll { key -> key.startsWith(prefix) }
        }

        /**
         * The node's original file name, asked of Proton once per node: a name that is missing is
         * remembered as missing, and concurrent askers share one round trip. A failed request is
         * not remembered, so the next open asks again.
         */
        suspend fun getOriginalFileName(
            userId: UserId,
            nodeUid: String,
        ): String? {
            val key = key(userId, nodeUid)
            originalFileNames[key]?.let { return it.name }
            val lookup =
                fileNameLookups.computeIfAbsent(key) {
                    transferScope.async(start = CoroutineStart.LAZY) { fetchOriginalFileName(userId, nodeUid, key) }
                }
            lookup.invokeOnCompletion { fileNameLookups.remove(key, lookup) }
            lookup.start()
            return lookup.await()
        }

        private suspend fun fetchOriginalFileName(
            userId: UserId,
            nodeUid: String,
            key: String,
        ): String? =
            try {
                val name =
                    clientProvider
                        .get(userId)
                        .getNode(NodeUid(nodeUid))
                        ?.originalFileName()
                originalFileNames[key] = ResolvedName(name)
                name
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.ORIGINAL_NAME_LOAD, error)
                null
            }

        private class ResolvedName(
            val name: String?,
        )

        private fun key(
            userId: UserId,
            nodeUid: String,
        ): String = "${userId.id}:$nodeUid"
    }

/**
 * One transfer shared by every caller asking for the same node. Waiters register atomically
 * through [tryJoin]: once the last waiter has left and taken the transfer down, later joiners
 * are refused and start afresh instead of waiting on a cancelled transfer or, worse, joining
 * between the count reaching zero and the cancellation.
 */
internal class ProtonSharedDownload<T>(
    val deferred: Deferred<T>,
) {
    private val waiters = AtomicInteger()

    /** False once the last waiter left and took the transfer down; the caller starts a new one. */
    fun tryJoin(): Boolean {
        while (true) {
            val current = waiters.get()
            if (current == ABANDONED) return false
            if (waiters.compareAndSet(current, current + 1)) return true
        }
    }

    val isAbandoned: Boolean get() = waiters.get() == ABANDONED

    /** Waits for the transfer after a successful [tryJoin]; leaving is what may cancel it. */
    suspend fun await(): T =
        try {
            deferred.await()
        } finally {
            leave()
        }

    /** Cancels the transfer for every waiter and waits for it to stop. */
    suspend fun forget() {
        deferred.cancel()
        deferred.join()
    }

    private fun leave() {
        // The compare-and-set loses to a joiner that arrived in the meantime, which keeps the
        // transfer alive for that joiner.
        if (waiters.decrementAndGet() == 0 && !deferred.isCompleted && waiters.compareAndSet(0, ABANDONED)) {
            deferred.cancel()
        }
    }

    private companion object {
        const val ABANDONED = -1
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
