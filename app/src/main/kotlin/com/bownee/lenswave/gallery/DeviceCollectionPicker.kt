package com.bownee.lenswave.gallery

internal data class DevicePickerPlacement(
    val startMargin: Int,
    val width: Int,
    val bottomMargin: Int,
)

object DeviceCollectionPicker {
    val collections = DeviceCollection.entries.toList()

    fun menuLabelRes(collection: DeviceCollection): Int = when (collection) {
        DeviceCollection.ALL -> com.bownee.lenswave.R.string.collection_all_device_photos
        else -> collection.labelRes
    }

    internal fun shouldOpenMenu(destination: GalleryDestination): Boolean =
        destination is GalleryDestination.Device

    internal fun anchoredPlacement(
        rootHeight: Int,
        rootWidth: Int,
        sourceBarLeft: Int,
        sourceBarTop: Int,
        anchorLeft: Int,
        anchorWidth: Int,
        verticalGap: Int,
        isRtl: Boolean = false,
    ) = DevicePickerPlacement(
        startMargin = (sourceBarLeft + anchorLeft).let { physicalLeft ->
            if (isRtl) rootWidth - physicalLeft - anchorWidth else physicalLeft
        },
        width = anchorWidth,
        bottomMargin = rootHeight - sourceBarTop + verticalGap,
    )
}
