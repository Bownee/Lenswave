package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonMediaTag

internal enum class GallerySection {
    PHOTOS,
    ALBUMS,
    TRASH,
}

internal enum class GallerySourceFilter {
    ALL,
    PROTON,
    DEVICE,
}

internal data class GalleryPhotoFilters(
    val source: GallerySourceFilter,
    val protonTag: ProtonMediaTag? = null,
    val deviceCollection: DeviceCollection = DeviceCollection.ALL,
)

internal object GalleryNavigationPolicy {
    fun section(destination: GalleryDestination): GallerySection = when (destination) {
        GalleryDestination.ProtonAlbums,
        is GalleryDestination.ProtonAlbumPhotos,
        -> GallerySection.ALBUMS

        is GalleryDestination.Trash -> GallerySection.TRASH
        else -> GallerySection.PHOTOS
    }

    fun photoFilters(
        destination: GalleryDestination,
        selectedDeviceCollection: DeviceCollection,
    ): GalleryPhotoFilters = when (destination) {
        GalleryDestination.Combined -> GalleryPhotoFilters(
            source = GallerySourceFilter.ALL,
            deviceCollection = selectedDeviceCollection,
        )
        is GalleryDestination.Device -> GalleryPhotoFilters(
            source = GallerySourceFilter.DEVICE,
            deviceCollection = destination.collection,
        )

        GalleryDestination.ProtonTimeline -> GalleryPhotoFilters(
            source = GallerySourceFilter.PROTON,
            deviceCollection = selectedDeviceCollection,
        )
        is GalleryDestination.ProtonTag -> GalleryPhotoFilters(
            source = GallerySourceFilter.PROTON,
            protonTag = destination.tag,
            deviceCollection = selectedDeviceCollection,
        )

        else -> GalleryPhotoFilters(
            source = if (destination.space == GallerySpace.DEVICE) {
                GallerySourceFilter.DEVICE
            } else {
                GallerySourceFilter.PROTON
            },
            deviceCollection = selectedDeviceCollection,
        )
    }

    fun photoDestination(filters: GalleryPhotoFilters): GalleryDestination = when (filters.source) {
        GallerySourceFilter.ALL -> GalleryDestination.Combined
        GallerySourceFilter.PROTON -> filters.protonTag
            ?.let(GalleryDestination::ProtonTag)
            ?: GalleryDestination.ProtonTimeline

        GallerySourceFilter.DEVICE -> GalleryDestination.Device(filters.deviceCollection)
    }
}
