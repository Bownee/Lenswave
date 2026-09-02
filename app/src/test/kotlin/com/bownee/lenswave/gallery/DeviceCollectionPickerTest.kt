package com.bownee.lenswave.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCollectionPickerTest {
    @Test
    fun `all device collections are available exactly once`() {
        assertEquals(DeviceCollection.entries.toSet(), DeviceCollectionPicker.collections.toSet())
        assertEquals(
            DeviceCollectionPicker.collections.size,
            DeviceCollectionPicker.collections.distinct().size,
        )
    }

    @Test
    fun `all collection has a clear menu label`() {
        assertEquals(com.bownee.lenswave.R.string.collection_all_device_photos, DeviceCollectionPicker.menuLabelRes(DeviceCollection.ALL))
    }

    @Test
    fun `device destination opens menu while trash returns to collection`() {
        assertTrue(DeviceCollectionPicker.shouldOpenMenu(GalleryDestination.Device()))
        assertFalse(DeviceCollectionPicker.shouldOpenMenu(GalleryDestination.Trash(PhotoSource.DEVICE)))
    }

    @Test
    fun `menu is anchored exactly to device tab`() {
        assertEquals(
            DevicePickerPlacement(startMargin = 28, width = 180, bottomMargin = 208),
            DeviceCollectionPicker.anchoredPlacement(
                rootHeight = 2_400,
                rootWidth = 1_080,
                sourceBarLeft = 8,
                sourceBarTop = 2_200,
                anchorLeft = 20,
                anchorWidth = 180,
                verticalGap = 8,
            ),
        )
    }

    @Test
    fun `rtl menu is anchored to logical start`() {
        val placement = DeviceCollectionPicker.anchoredPlacement(
            rootHeight = 2_400,
            rootWidth = 1_080,
            sourceBarLeft = 8,
            sourceBarTop = 2_200,
            anchorLeft = 20,
            anchorWidth = 180,
            verticalGap = 8,
            isRtl = true,
        )

        assertEquals(872, placement.startMargin)
    }
}
