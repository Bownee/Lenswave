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

@Singleton
internal class ProtonTimelineRepository @Inject constructor(
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonTimelineCache,
    private val syncPipeline: ProtonPhotoSyncPipeline,
    private val snapshots: ProtonSnapshotCoordinator,
) {
    private val syncMutex = Mutex()
    private val mutableState = MutableStateFlow(ProtonGalleryState())

    val state: StateFlow<ProtonGalleryState> = mutableState.asStateFlow()

    fun loadCached(userId: UserId) {
        emit(userId, cache.readIndex(userId.id), syncing = false)
    }

    suspend fun sync(userId: UserId, forceRemote: Boolean, maxThumbnailDownloads: Int? = null) = syncMutex.withLock {
        val existing = cache.readIndex(userId.id)
        emit(userId, existing, syncing = true)
        try {
            val photosClient = clientProvider.get(userId)
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id, ProtonSyncSource.TIMELINE, SYNC_KEY, forceRemote, cache.hasTimelineSnapshot(userId.id),
            )
            val photos = syncPipeline.synchronize(
                photosClient = photosClient,
                userId = userId,
                existing = existing,
                shouldEnumerate = shouldEnumerate,
                maxThumbnailDownloads = maxThumbnailDownloads,
                enumerate = {
                    photosClient.enumerateTimeline().toList().map { item ->
                    ProtonGalleryPhoto(
                        nodeUid = item.nodeUid.value,
                        captureTimeEpochSeconds = item.captureTime.epochSecond,
                        hasThumbnail = cache.thumbnailIsDecodable(userId.id, item.nodeUid.value),
                    )
                    }
                },
                prepareSnapshot = { remotePhotos ->
                    cache.reconcilePhotos(
                        userId = userId.id,
                        cachedNodeUids = existing.map(ProtonGalleryPhoto::nodeUid),
                        remoteNodeUids = remotePhotos.map(ProtonGalleryPhoto::nodeUid),
                    )
                },
                commitSnapshot = { cache.writeIndex(userId.id, it) },
                commitEnumeration = { snapshots.commit(userId.id, SYNC_KEY) },
                onProgress = { emit(userId, it, syncing = true) },
            )
            emit(userId, photos, syncing = false)
        } catch (error: CancellationException) {
            mutableState.value = mutableState.value.copy(syncing = false)
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("timeline-sync", error)
            mutableState.value = mutableState.value.copy(
                syncing = false,
                errorMessage = "Could not refresh Proton Photos",
            )
        }
    }

    internal suspend fun removePhotos(userId: UserId, nodeUids: Set<String>): Unit = syncMutex.withLock {
        if (nodeUids.isEmpty()) return@withLock
        cache.removePhotos(userId.id, nodeUids)
        emit(userId, mutableState.value.photos.filterNot { it.nodeUid in nodeUids }, syncing = false)
    }

    internal fun reset() {
        mutableState.value = ProtonGalleryState()
    }

    internal fun updateThumbnailWorkStatus(status: ProtonThumbnailWorkStatus?) {
        mutableState.value = mutableState.value.copy(thumbnailWorkStatus = status)
    }

    private fun emit(userId: UserId, photos: List<ProtonGalleryPhoto>, syncing: Boolean) {
        val workerStatus = mutableState.value.thumbnailWorkStatus
            .takeIf { status -> status is ProtonThumbnailWorkStatus.Running }
        mutableState.value = ProtonGalleryState(
            userId = userId.id,
            photos = photos.toList(),
            syncing = syncing,
            downloadedThumbnailCount = photos.count(ProtonGalleryPhoto::hasThumbnail),
            thumbnailWorkStatus = workerStatus,
        )
    }

    private companion object {
        const val SYNC_KEY = "timeline"
    }
}
