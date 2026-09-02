package com.bownee.lenswave.gallery

import androidx.annotation.StringRes
import com.bownee.lenswave.R
import com.bownee.lenswave.proton.ProtonAlbumReference

sealed interface GalleryDestination {
    val space: GallerySpace

    data object Combined : GalleryDestination {
        override val space = GallerySpace.COMBINED
    }

    data class Device(
        val collection: DeviceCollection = DeviceCollection.CAMERA,
    ) : GalleryDestination {
        override val space = GallerySpace.DEVICE
    }

    data object ProtonTimeline : GalleryDestination {
        override val space = GallerySpace.PROTON
    }

    data object ProtonAlbums : GalleryDestination {
        override val space = GallerySpace.PROTON
    }

    data class ProtonAlbumPhotos(
        val album: ProtonAlbumReference,
    ) : GalleryDestination {
        override val space = GallerySpace.PROTON
    }

    data class Trash(
        val source: PhotoSource,
    ) : GalleryDestination {
        override val space = when (source) {
            PhotoSource.DEVICE -> GallerySpace.DEVICE
            PhotoSource.PROTON -> GallerySpace.PROTON
        }
    }
}

enum class GallerySpace(@param:StringRes val labelRes: Int) {
    COMBINED(R.string.space_combined),
    PROTON(R.string.space_proton),
    DEVICE(R.string.space_device),
}

object GalleryDestinations {
    fun defaultFor(space: GallerySpace): GalleryDestination = when (space) {
        GallerySpace.COMBINED -> GalleryDestination.Combined
        GallerySpace.PROTON -> GalleryDestination.ProtonTimeline
        GallerySpace.DEVICE -> GalleryDestination.Device()
    }
}
