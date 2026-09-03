package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
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
    private val snapshots: ProtonSnapshotCoordinator,
    private val tagListings: ProtonTagListingClient,
) {
    private val syncMutex = Mutex()
    private val tagMutexes = ProtonMediaTag.entries.associateWith { Mutex() }
    private val mutableState = MutableStateFlow(ProtonGalleryState())

    val state: StateFlow<ProtonGalleryState> = mutableState.asStateFlow()

    fun loadCached(userId: UserId) {
        val tagStates = ProtonMediaTag.entries.mapNotNull { tag ->
            if (!cache.hasTagSnapshot(userId.id, tag)) return@mapNotNull null
            tag to ProtonTagState(
                photos = cache.readTag(userId.id, tag),
                hasLoaded = true,
            )
        }.toMap()
        emit(
            userId = userId,
            photos = cache.readIndex(userId.id),
            hasLoaded = cache.hasTimelineSnapshot(userId.id),
            syncing = false,
            tags = tagStates,
        )
    }

    suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) = syncMutex.withLock {
        val existing = cache.readIndex(userId.id)
        val hasCachedSnapshot = cache.hasTimelineSnapshot(userId.id)
        try {
            val shouldEnumerate = snapshots.shouldEnumerate(
                userId.id,
                ProtonSyncSource.TIMELINE,
                ProtonSyncKeys.TIMELINE,
                forceRemote,
                hasCachedSnapshot,
            )
            if (!shouldEnumerate) {
                emit(userId, existing, hasLoaded = true, syncing = false)
                return@withLock
            }
            emit(userId, existing, hasLoaded = hasCachedSnapshot, syncing = true)
            val photosClient = clientProvider.get(userId)
            val photos = photosClient.enumerateTimeline().toList().map { item ->
                ProtonGalleryPhoto(
                    nodeUid = item.nodeUid.value,
                    captureTimeEpochSeconds = item.captureTime.epochSecond,
                    hasThumbnail = cache.thumbnailExists(userId.id, item.nodeUid.value),
                )
            }
            cache.reconcilePhotos(
                userId = userId.id,
                cachedNodeUids = existing.map(ProtonGalleryPhoto::nodeUid),
                remoteNodeUids = photos.map(ProtonGalleryPhoto::nodeUid),
            )
            cache.writeIndex(userId.id, photos)
            snapshots.commit(userId.id, ProtonSyncKeys.TIMELINE)
            val remoteNodeUids = photos.mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)
            val reconciledTags = mutableState.value.tags.mapValues { (_, tagState) ->
                tagState.copy(photos = tagState.photos.filter { it.nodeUid in remoteNodeUids })
            }
            emit(userId, photos, hasLoaded = true, syncing = false, tags = reconciledTags)
        } catch (error: CancellationException) {
            mutableState.value = mutableState.value.copy(syncing = false)
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.TIMELINE_SYNC, error)
            mutableState.value = mutableState.value.copy(
                syncing = false,
                refreshFailed = true,
            )
        }
    }

    suspend fun syncTagMetadata(userId: UserId, tag: ProtonMediaTag, forceRemote: Boolean) =
        tagMutexes.getValue(tag).withLock {
            val existing = cache.readTag(userId.id, tag)
            val hasCachedSnapshot = cache.hasTagSnapshot(userId.id, tag)
            val syncKey = ProtonSyncKeys.timelineTag(tag)
            try {
                val shouldEnumerate = snapshots.shouldEnumerate(
                    userId.id,
                    ProtonSyncSource.TIMELINE,
                    syncKey,
                    forceRemote,
                    hasCachedSnapshot,
                )
                if (!shouldEnumerate) {
                    updateTag(tag, ProtonTagState(existing, hasLoaded = true))
                    return@withLock
                }
                updateTag(tag, ProtonTagState(existing, hasLoaded = hasCachedSnapshot, syncing = true))
                val volumeId = volumeId(existing) ?: volumeId(mutableState.value.photos)
                val photos = if (volumeId == null && mutableState.value.hasLoaded &&
                    mutableState.value.photos.isEmpty()
                ) {
                    emptyList()
                } else {
                    tagListings.list(
                        userId,
                        requireNotNull(volumeId) { "Cannot determine the Proton Photos volume" },
                        tag,
                    ).map { photo ->
                        photo.copy(hasThumbnail = cache.thumbnailExists(userId.id, photo.nodeUid))
                    }
                }
                cache.writeTag(userId.id, tag, photos)
                snapshots.commit(userId.id, syncKey)
                updateTag(tag, ProtonTagState(photos, hasLoaded = true))
            } catch (error: CancellationException) {
                updateTag(
                    tag,
                    mutableState.value.tags[tag]?.copy(syncing = false) ?: ProtonTagState(),
                )
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.tagSync(tag), error)
                updateTag(
                    tag,
                    ProtonTagState(
                        photos = existing,
                        hasLoaded = hasCachedSnapshot,
                        refreshFailed = true,
                    ),
                )
            }
        }

    internal fun markThumbnailsAvailable(userId: UserId, nodeUids: Set<String>) {
        markThumbnails(userId, nodeUids, available = true)
    }

    internal fun markThumbnailsUnavailable(userId: UserId, nodeUids: Set<String>) {
        markThumbnails(userId, nodeUids, available = false)
    }

    private fun markThumbnails(userId: UserId, nodeUids: Set<String>, available: Boolean) {
        if (nodeUids.isEmpty()) return
        mutableState.update { state ->
            if (state.userId != userId.id) return@update state
            val photos = state.photos.withThumbnailAvailability(
                nodeUids,
                available,
                nodeUid = ProtonGalleryPhoto::nodeUid,
                hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
                copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
            ) ?: return@update state
            state.copy(
                photos = photos,
                tags = state.tags.mapValues { (_, tagState) ->
                    tagState.copy(photos = tagState.photos.map { photo ->
                        if (photo.nodeUid in nodeUids) photo.copy(hasThumbnail = available) else photo
                    })
                },
            )
        }
    }

    internal fun setFavorite(userId: UserId, nodeUids: Set<String>, favorite: Boolean) {
        if (nodeUids.isEmpty() || mutableState.value.userId != userId.id) return
        val current = mutableState.value
        val currentFavoriteState = current.tags[ProtonMediaTag.FAVORITES]
        val currentFavorites = currentFavoriteState?.photos.orEmpty()
        val nextFavorites = if (favorite) {
            val knownPhotos = current.photos.associateBy(ProtonGalleryPhoto::nodeUid)
            (currentFavorites + nodeUids.map { nodeUid ->
                knownPhotos[nodeUid] ?: ProtonGalleryPhoto(
                    nodeUid = nodeUid,
                    captureTimeEpochSeconds = 0L,
                    hasThumbnail = cache.thumbnailExists(userId.id, nodeUid),
                )
            }).distinctBy(ProtonGalleryPhoto::nodeUid)
                .sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
        } else {
            currentFavorites.filterNot { it.nodeUid in nodeUids }
        }
        if (currentFavoriteState?.hasLoaded == true) {
            cache.writeTag(userId.id, ProtonMediaTag.FAVORITES, nextFavorites)
        }
        updateTag(
            ProtonMediaTag.FAVORITES,
            ProtonTagState(
                photos = nextFavorites,
                hasLoaded = currentFavoriteState?.hasLoaded == true,
                refreshFailed = currentFavoriteState?.refreshFailed == true,
            ),
        )
    }

    internal suspend fun removePhotos(userId: UserId, nodeUids: Set<String>): Unit = syncMutex.withLock {
        if (nodeUids.isEmpty()) return@withLock
        cache.removePhotos(userId.id, nodeUids)
        mutableState.value = mutableState.value.copy(
            tags = mutableState.value.tags.mapValues { (_, tagState) ->
                tagState.copy(photos = tagState.photos.filterNot { it.nodeUid in nodeUids })
            },
        )
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
        tags: Map<ProtonMediaTag, ProtonTagState> = mutableState.value.tags,
    ) {
        val workerStatus = mutableState.value.thumbnailWorkStatus
            .takeIf { status -> status is ProtonThumbnailWorkStatus.Running }
        mutableState.value = ProtonGalleryState(
            userId = userId.id,
            photos = photos.toList(),
            hasLoaded = hasLoaded,
            syncing = syncing,
            thumbnailWorkStatus = workerStatus,
            tags = tags,
        )
    }

    private fun updateTag(tag: ProtonMediaTag, state: ProtonTagState) {
        mutableState.update { gallery -> gallery.copy(tags = gallery.tags + (tag to state)) }
    }

    private fun volumeId(photos: List<ProtonGalleryPhoto>): String? = photos.firstOrNull()
        ?.nodeUid
        ?.substringBefore('~')
        ?.takeIf(String::isNotBlank)
}
