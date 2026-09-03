package com.bownee.lenswave.gallery

import androidx.annotation.StringRes
import com.bownee.lenswave.R

enum class PhotoSource {
    DEVICE,
    PROTON,
}

enum class MediaKind {
    IMAGE,
    VIDEO,
}

enum class DeviceCollection(@param:StringRes val labelRes: Int) {
    ALL(R.string.collection_all),
    CAMERA(R.string.collection_camera),
    SCREENSHOTS(R.string.collection_screenshots),
    DOWNLOADS(R.string.collection_downloads),
    WHATSAPP(R.string.collection_whatsapp),
    OTHER(R.string.collection_other),
}

sealed interface PhotoReplica {
    val source: PhotoSource
    val mediaKind: MediaKind
    val isTrashed: Boolean

    data class Device(
        val uri: String,
        val collection: DeviceCollection,
        val sizeBytes: Long,
        val modifiedAtEpochMillis: Long,
        override val mediaKind: MediaKind = MediaKind.IMAGE,
        override val isTrashed: Boolean = false,
    ) : PhotoReplica {
        override val source = PhotoSource.DEVICE
    }

    data class Proton(
        val nodeUid: String,
        val hasThumbnail: Boolean,
        override val mediaKind: MediaKind = MediaKind.IMAGE,
        val tags: Set<com.bownee.lenswave.proton.ProtonMediaTag> = emptySet(),
        override val isTrashed: Boolean = false,
    ) : PhotoReplica {
        override val source = PhotoSource.PROTON
    }
}

/**
 * One logical photo shown by the gallery.
 *
 * A logical photo can have a device replica, one or more Proton replicas, or both. Keeping
 * storage-specific values in [PhotoReplica] prevents impossible combinations such as a device
 * item with a missing URI and makes combined-gallery provenance explicit.
 */
data class GalleryAsset(
    val stableId: String,
    val capturedAtEpochMillis: Long,
    val displayName: String = "",
    val primaryReplica: PhotoReplica,
    val replicas: List<PhotoReplica> = listOf(primaryReplica),
) {
    init {
        require(replicas.isNotEmpty()) { "A gallery asset must have at least one replica" }
        require(primaryReplica in replicas) { "The primary replica must be included in replicas" }
    }

    val source: PhotoSource
        get() = primaryReplica.source

    val mediaKind: MediaKind
        get() = primaryReplica.mediaKind

    val deviceReplica: PhotoReplica.Device?
        get() = replicas.filterIsInstance<PhotoReplica.Device>().firstOrNull()

    val protonReplicas: List<PhotoReplica.Proton>
        get() = replicas.filterIsInstance<PhotoReplica.Proton>()

    val uri: String?
        get() = (primaryReplica as? PhotoReplica.Device)?.uri

    val protonNodeUid: String?
        get() = (primaryReplica as? PhotoReplica.Proton)?.nodeUid

    val protonBackingNodeUids: List<String>
        get() = protonReplicas
            .takeIf { source == PhotoSource.DEVICE }
            .orEmpty()
            .map(PhotoReplica.Proton::nodeUid)

    val hasThumbnail: Boolean
        get() = when (val replica = primaryReplica) {
            is PhotoReplica.Device -> true
            is PhotoReplica.Proton -> replica.hasThumbnail
        }

    val deviceCollection: DeviceCollection?
        get() = deviceReplica?.collection

    val sizeBytes: Long
        get() = deviceReplica?.sizeBytes ?: 0L

    val modifiedAtEpochMillis: Long
        get() = deviceReplica?.modifiedAtEpochMillis ?: 0L

    val isTrashed: Boolean
        get() = primaryReplica.isTrashed

    val isStoredInProton: Boolean
        get() = protonReplicas.isNotEmpty()

    val isFavorite: Boolean
        get() = protonReplicas.any { com.bownee.lenswave.proton.ProtonMediaTag.FAVORITES in it.tags }

    fun withReplicas(additionalReplicas: Iterable<PhotoReplica>): GalleryAsset {
        val mergedReplicas = (replicas + additionalReplicas).distinctBy { replica ->
            when (replica) {
                is PhotoReplica.Device -> "device:${replica.uri}"
                is PhotoReplica.Proton -> "proton:${replica.nodeUid}"
            }
        }
        return copy(replicas = mergedReplicas)
    }

    companion object {
        fun device(
            stableId: String,
            capturedAtEpochMillis: Long,
            displayName: String,
            uri: String,
            collection: DeviceCollection,
            sizeBytes: Long,
            modifiedAtEpochMillis: Long,
            mediaKind: MediaKind = MediaKind.IMAGE,
            isTrashed: Boolean = false,
        ): GalleryAsset {
            val replica = PhotoReplica.Device(
                uri = uri,
                collection = collection,
                sizeBytes = sizeBytes,
                modifiedAtEpochMillis = modifiedAtEpochMillis,
                mediaKind = mediaKind,
                isTrashed = isTrashed,
            )
            return GalleryAsset(
                stableId = stableId,
                capturedAtEpochMillis = capturedAtEpochMillis,
                displayName = displayName,
                primaryReplica = replica,
            )
        }

        fun proton(
            stableId: String,
            capturedAtEpochMillis: Long,
            displayName: String = "",
            nodeUid: String,
            hasThumbnail: Boolean,
            mediaKind: MediaKind = MediaKind.IMAGE,
            tags: Set<com.bownee.lenswave.proton.ProtonMediaTag> = emptySet(),
            isTrashed: Boolean = false,
        ): GalleryAsset {
            val replica = PhotoReplica.Proton(
                nodeUid = nodeUid,
                hasThumbnail = hasThumbnail,
                mediaKind = mediaKind,
                tags = tags,
                isTrashed = isTrashed,
            )
            return GalleryAsset(
                stableId = stableId,
                capturedAtEpochMillis = capturedAtEpochMillis,
                displayName = displayName,
                primaryReplica = replica,
            )
        }
    }
}
