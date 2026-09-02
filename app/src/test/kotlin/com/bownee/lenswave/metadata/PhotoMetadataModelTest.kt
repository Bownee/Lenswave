package com.bownee.lenswave.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMetadataModelTest {
    @Test
    fun `coordinates use stable decimal formatting`() {
        val location = PhotoLocation(46.94809, 7.44744)

        assertEquals("46.94809, 7.44744", location.coordinateText())
    }

    @Test
    fun `map action retains exact coordinates`() {
        val item = PhotoMetadataItem(
            label = "Coordinates",
            value = "46.94809, 7.44744",
            action = PhotoMetadataAction.OpenMap(46.94809, 7.44744),
        )

        assertTrue(item.action is PhotoMetadataAction.OpenMap)
        val action = item.action as PhotoMetadataAction.OpenMap
        assertEquals(46.94809, action.latitude, 0.0)
        assertEquals(7.44744, action.longitude, 0.0)
    }
}
