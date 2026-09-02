package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid

@Singleton
internal class ProtonTrashRepository @Inject constructor(
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonTrashCache,
    private val snapshots: ProtonSnapshotCoordinator,
) {
    private val syncMutex = Mutex()
    private val mutableState = MutableStateFlow(ProtonTrashState())

    val state: StateFlow<ProtonTrashState> = mutableState.asStateFlow()

    fun loadCached(userId: UserId) {
        emit(
            userId,
            cache.readTrash(userId.id),
            syncing = false,
            hasLoaded = cache.hasTrashSnapshot(userId.id),
        )
    }

    suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) = syncMutex.withLock {
        val existing = cache.readTrash(userId.id)
        emit(
            userId,
            existing,
            syncing = true,
            hasLoaded = cache.hasTrashSnapshot(userId.id),
        )
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

    internal fun markThumbnailAvailable(userId: UserId, nodeUid: String) {
        mutableState.update { state ->
            if (state.userId != userId.id) return@update state
            val position = state.photos.indexOfFirst { photo ->
                photo.nodeUid == nodeUid && !photo.hasThumbnail
            }
            if (position < 0) return@update state
            val photos = state.photos.toMutableList()
            photos[position] = photos[position].copy(hasThumbnail = true)
            state.copy(
                photos = photos,
                downloadedThumbnailCount = state.downloadedThumbnailCount + 1,
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

    private fun emit(
        userId: UserId,
        photos: List<ProtonTrashPhoto>,
        syncing: Boolean,
        hasLoaded: Boolean = true,
    ) {
        mutableState.value = ProtonTrashState(
            userId = userId.id,
            photos = photos.toList(),
            hasLoaded = hasLoaded,
            syncing = syncing,
            downloadedThumbnailCount = photos.count(ProtonTrashPhoto::hasThumbnail),
        )
    }

    private companion object {
        const val SYNC_KEY = "trash"
    }
}
