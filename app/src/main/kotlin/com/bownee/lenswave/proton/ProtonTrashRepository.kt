package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid

@Singleton
internal class ProtonTrashRepository @Inject constructor(
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonTrashCache,
    private val downloads: ProtonDownloadRepository,
    private val snapshots: ProtonSnapshotCoordinator,
) {
    private val syncMutex = Mutex()
    private val mutableState = MutableStateFlow(ProtonTrashState())

    val state: StateFlow<ProtonTrashState> = mutableState.asStateFlow()

    fun loadCached(userId: UserId) {
        emit(userId, cache.readTrash(userId.id), syncing = false)
    }

    suspend fun sync(userId: UserId, forceRemote: Boolean) = syncMutex.withLock {
        val existing = cache.readTrash(userId.id)
        emit(userId, existing, syncing = true)
        try {
            val photosClient = clientProvider.get(userId)
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id, ProtonSyncSource.TRASH, SYNC_KEY, forceRemote, cache.hasTrashSnapshot(userId.id),
            )
            val photos = if (shouldEnumerate) {
                photosClient.enumerateTrashNodeUids().toList().mapNotNull { nodeUid ->
                    requireNotNull(photosClient.getNode(nodeUid)) {
                        "Proton returned no node for ${nodeUid.value}"
                    }.toProtonTrashPhoto(
                        hasThumbnail = cache.thumbnailIsDecodable(userId.id, nodeUid.value),
                    )
                }.sortedByDescending(ProtonTrashPhoto::trashedAtEpochSeconds).toMutableList().also {
                    cache.writeTrash(userId.id, it)
                    snapshots.commit(userId.id, SYNC_KEY)
                }
            } else {
                existing.toMutableList()
            }

            emit(userId, photos, syncing = true)
            val positions = photos.indices.associateBy { photos[it].nodeUid }
            downloads.downloadMissingThumbnails(
                photosClient = photosClient,
                userId = userId,
                nodeUids = photos.filterNot(ProtonTrashPhoto::hasThumbnail).map(ProtonTrashPhoto::nodeUid),
                onStored = { nodeUid ->
                    positions[nodeUid]?.let { position ->
                        photos[position] = photos[position].copy(hasThumbnail = true)
                    }
                },
                onProgress = { emit(userId, photos, syncing = true) },
            )
            cache.writeTrash(userId.id, photos)
            emit(userId, photos, syncing = false)
        } catch (error: CancellationException) {
            mutableState.value = mutableState.value.copy(syncing = false)
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("trash-sync", error)
            mutableState.value = mutableState.value.copy(
                syncing = false,
                errorMessage = "Could not refresh Proton Trash",
            )
        }
    }

    suspend fun deletePermanently(
        userId: UserId,
        nodeUids: Collection<String>,
    ): ProtonDeleteResult = syncMutex.withLock {
        val requested = nodeUids.distinct()
        if (requested.isEmpty()) return@withLock ProtonDeleteResult()
        val results = clientProvider.get(userId).deleteNodes(requested.map(::NodeUid)).toList()
        val successful = results.filterIsInstance<NodeResultPair.Success>().map { it.nodeUid.value }.toSet()
        if (successful.isNotEmpty()) {
            cache.removePhotos(userId.id, successful)
            val remaining = mutableState.value.photos.filterNot { it.nodeUid in successful }
            cache.writeTrash(userId.id, remaining)
            emit(userId, remaining, syncing = false)
        }
        ProtonDeleteResult(
            deletedCount = successful.size,
            failedCount = results.count { it is NodeResultPair.Failure },
        )
    }

    internal fun reset() {
        mutableState.value = ProtonTrashState()
    }

    private fun emit(userId: UserId, photos: List<ProtonTrashPhoto>, syncing: Boolean) {
        mutableState.value = ProtonTrashState(
            userId = userId.id,
            photos = photos.toList(),
            syncing = syncing,
            downloadedThumbnailCount = photos.count(ProtonTrashPhoto::hasThumbnail),
        )
    }

    private companion object {
        const val SYNC_KEY = "trash"
    }
}
