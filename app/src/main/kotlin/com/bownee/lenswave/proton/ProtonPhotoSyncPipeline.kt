package com.bownee.lenswave.proton

import javax.inject.Inject
import javax.inject.Singleton

/** Shared transaction for metadata snapshots. */
@Singleton
internal class ProtonPhotoSyncPipeline @Inject constructor() {
    suspend fun synchronizeMetadata(
        enumerate: suspend () -> List<ProtonGalleryPhoto>,
        prepareSnapshot: (List<ProtonGalleryPhoto>) -> Unit = {},
        commitSnapshot: (List<ProtonGalleryPhoto>) -> Unit,
        commitEnumeration: () -> Unit,
    ): List<ProtonGalleryPhoto> = enumerate().toMutableList().also { remotePhotos ->
        prepareSnapshot(remotePhotos)
        commitSnapshot(remotePhotos)
        commitEnumeration()
    }
}
