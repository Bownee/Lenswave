package com.bownee.lenswave.proton

import javax.inject.Inject
import javax.inject.Singleton
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient

/** Shared transaction for snapshot enumeration, durable commit, and thumbnail hydration. */
@Singleton
internal class ProtonPhotoSyncPipeline @Inject constructor(
    private val downloads: ProtonDownloadRepository,
) {
    suspend fun synchronize(
        photosClient: ProtonPhotosClient,
        userId: UserId,
        existing: List<ProtonGalleryPhoto>,
        shouldEnumerate: Boolean,
        maxThumbnailDownloads: Int? = null,
        enumerate: suspend () -> List<ProtonGalleryPhoto>,
        prepareSnapshot: (List<ProtonGalleryPhoto>) -> Unit = {},
        commitSnapshot: (List<ProtonGalleryPhoto>) -> Unit,
        commitEnumeration: () -> Unit,
        onProgress: (List<ProtonGalleryPhoto>) -> Unit,
    ): List<ProtonGalleryPhoto> {
        val photos = if (shouldEnumerate) {
            enumerate().toMutableList().also { remotePhotos ->
                prepareSnapshot(remotePhotos)
                commitSnapshot(remotePhotos)
                commitEnumeration()
            }
        } else {
            existing.toMutableList()
        }

        onProgress(photos)
        val positions = photos.indices.associateBy { photos[it].nodeUid }
        downloads.downloadMissingThumbnails(
            photosClient = photosClient,
            userId = userId,
            nodeUids = photos.filterNot(ProtonGalleryPhoto::hasThumbnail).map(ProtonGalleryPhoto::nodeUid),
            maxDownloads = maxThumbnailDownloads,
            onStored = { nodeUid ->
                positions[nodeUid]?.let { position ->
                    photos[position] = photos[position].copy(hasThumbnail = true)
                }
            },
            onProgress = { onProgress(photos) },
        )
        commitSnapshot(photos)
        return photos
    }
}
