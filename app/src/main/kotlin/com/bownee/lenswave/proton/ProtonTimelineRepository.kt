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
        emit(
            userId = userId,
            photos = cache.readIndex(userId.id),
            hasLoaded = cache.hasTimelineSnapshot(userId.id),
            syncing = false,
        )
    }

    suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) = syncMutex.withLock {
        val existing = cache.readIndex(userId.id)
        val hasCachedSnapshot = cache.hasTimelineSnapshot(userId.id)
        try {
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id,
                ProtonSyncSource.TIMELINE,
                SYNC_KEY,
                forceRemote,
                hasCachedSnapshot,
            )
            if (!shouldEnumerate) {
                emit(userId, existing, hasLoaded = true, syncing = false)
                return@withLock
            }
            emit(userId, existing, hasLoaded = hasCachedSnapshot, syncing = true)
            val photosClient = clientProvider.get(userId)
            val photos = syncPipeline.synchronizeMetadata(
                enumerate = {
                    photosClient.enumerateTimeline().toList().map { item ->
                        ProtonGalleryPhoto(
                            nodeUid = item.nodeUid.value,
                            captureTimeEpochSeconds = item.captureTime.epochSecond,
                            hasThumbnail = cache.thumbnailExists(userId.id, item.nodeUid.value),
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
            )
            emit(userId, photos, hasLoaded = true, syncing = false)
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

    internal fun markThumbnailsAvailable(userId: UserId, nodeUids: Set<String>) {
        if (nodeUids.isEmpty()) return
        mutableState.update { state ->
            if (state.userId != userId.id) return@update state
            var completedCount = 0
            val photos = state.photos.map { photo ->
                if (photo.nodeUid !in nodeUids || photo.hasThumbnail) return@map photo
                completedCount++
                photo.copy(hasThumbnail = true)
            }
            if (completedCount == 0) return@update state
            state.copy(
                photos = photos,
                downloadedThumbnailCount = state.downloadedThumbnailCount + completedCount,
            )
        }
    }

    internal fun markThumbnailsUnavailable(userId: UserId, nodeUids: Set<String>) {
        if (nodeUids.isEmpty()) return
        mutableState.update { state ->
            if (state.userId != userId.id) return@update state
            var invalidatedCount = 0
            val photos = state.photos.map { photo ->
                if (photo.nodeUid !in nodeUids || !photo.hasThumbnail) return@map photo
                invalidatedCount++
                photo.copy(hasThumbnail = false)
            }
            if (invalidatedCount == 0) return@update state
            state.copy(
                photos = photos,
                downloadedThumbnailCount = (state.downloadedThumbnailCount - invalidatedCount).coerceAtLeast(0),
            )
        }
    }

    internal suspend fun removePhotos(userId: UserId, nodeUids: Set<String>): Unit = syncMutex.withLock {
        if (nodeUids.isEmpty()) return@withLock
        cache.removePhotos(userId.id, nodeUids)
        emit(
            userId,
            mutableState.value.photos.filterNot { it.nodeUid in nodeUids },
            hasLoaded = true,
            syncing = false,
        )
    }

    internal fun reset() {
        mutableState.value = ProtonGalleryState()
    }

    internal fun updateThumbnailWorkStatus(status: ProtonThumbnailWorkStatus?) {
        mutableState.value = mutableState.value.copy(thumbnailWorkStatus = status)
    }

    private fun emit(
        userId: UserId,
        photos: List<ProtonGalleryPhoto>,
        hasLoaded: Boolean,
        syncing: Boolean,
    ) {
        val workerStatus = mutableState.value.thumbnailWorkStatus
            .takeIf { status -> status is ProtonThumbnailWorkStatus.Running }
        mutableState.value = ProtonGalleryState(
            userId = userId.id,
            photos = photos.toList(),
            hasLoaded = hasLoaded,
            syncing = syncing,
            downloadedThumbnailCount = photos.count(ProtonGalleryPhoto::hasThumbnail),
            thumbnailWorkStatus = workerStatus,
        )
    }

    private companion object {
        const val SYNC_KEY = "timeline"
    }
}
