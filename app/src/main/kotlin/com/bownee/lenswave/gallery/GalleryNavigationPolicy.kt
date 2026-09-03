package com.bownee.lenswave.gallery

import androidx.annotation.StringRes
import com.bownee.lenswave.R

enum class GalleryTab {
    PHOTOS,
    LIBRARY,
}

/** The segmented source switch shown above a timeline or Trash. */
enum class GallerySource(@param:StringRes val labelRes: Int) {
    ALL(R.string.source_all),
    PROTON(R.string.source_proton),
    DEVICE(R.string.source_device),
}

object GalleryNavigationPolicy {
    fun tab(destination: GalleryDestination): GalleryTab = when (destination) {
        GalleryDestination.Combined,
        GalleryDestination.ProtonTimeline,
        -> GalleryTab.PHOTOS

        is GalleryDestination.Device -> if (destination.collection == DeviceCollection.ALL) {
            GalleryTab.PHOTOS
        } else {
            GalleryTab.LIBRARY
        }

        else -> GalleryTab.LIBRARY
    }

    fun timeline(source: GallerySource): GalleryDestination = when (source) {
        GallerySource.ALL -> GalleryDestination.Combined
        GallerySource.PROTON -> GalleryDestination.ProtonTimeline
        GallerySource.DEVICE -> GalleryDestination.Device()
    }

    /** Sources the destination can switch between in place; empty when it offers none. */
    fun sources(destination: GalleryDestination, supportsDeviceTrash: Boolean): List<GallerySource> = when {
        tab(destination) == GalleryTab.PHOTOS -> GallerySource.entries
        destination is GalleryDestination.Trash -> listOfNotNull(
            GallerySource.PROTON,
            GallerySource.DEVICE.takeIf { supportsDeviceTrash },
        )
        else -> emptyList()
    }

    fun selectedSource(destination: GalleryDestination): GallerySource? = when (destination) {
        GalleryDestination.Combined -> GallerySource.ALL
        GalleryDestination.ProtonTimeline -> GallerySource.PROTON
        is GalleryDestination.Device -> GallerySource.DEVICE.takeIf {
            destination.collection == DeviceCollection.ALL
        }
        is GalleryDestination.Trash -> when (destination.source) {
            PhotoSource.PROTON -> GallerySource.PROTON
            PhotoSource.DEVICE -> GallerySource.DEVICE
        }
        else -> null
    }

    fun withSource(destination: GalleryDestination, source: GallerySource): GalleryDestination =
        if (destination is GalleryDestination.Trash) {
            GalleryDestination.Trash(
                if (source == GallerySource.DEVICE) PhotoSource.DEVICE else PhotoSource.PROTON,
            )
        } else {
            timeline(source)
        }

    /** The screen Back returns to, or null when the destination is a tab root. */
    fun parent(destination: GalleryDestination): GalleryDestination? = when {
        destination == GalleryDestination.Library -> null
        tab(destination) == GalleryTab.LIBRARY -> GalleryDestination.Library
        else -> null
    }

    /** The tab root remembered across app restarts instead of a deep collection. */
    fun root(destination: GalleryDestination): GalleryDestination = when (tab(destination)) {
        GalleryTab.PHOTOS -> destination
        GalleryTab.LIBRARY -> GalleryDestination.Library
    }

    fun requiresProton(destination: GalleryDestination): Boolean = when (destination) {
        GalleryDestination.Combined,
        GalleryDestination.ProtonTimeline,
        is GalleryDestination.ProtonTag,
        is GalleryDestination.ProtonAlbumPhotos,
        -> true

        is GalleryDestination.Trash -> destination.source == PhotoSource.PROTON
        GalleryDestination.Library,
        is GalleryDestination.Device,
        -> false
    }

    /** Where a destination lands once Proton is no longer connected. */
    fun withoutProton(destination: GalleryDestination): GalleryDestination = when {
        !requiresProton(destination) -> destination
        tab(destination) == GalleryTab.PHOTOS -> GalleryDestination.Device()
        else -> GalleryDestination.Library
    }
}
