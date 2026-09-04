package com.bownee.lenswave.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifValueFormatterTest {
    @Test
    fun `fast exposures become shutter fractions`() {
        assertEquals("1/2890", ExifValueFormatter.exposureTime(3.46e-4))
        assertEquals("1/60", ExifValueFormatter.exposureTime(1.0 / 60.0))
        assertEquals("1/2", ExifValueFormatter.exposureTime(0.5))
    }

    @Test
    fun `slow exposures stay in seconds`() {
        assertEquals("1", ExifValueFormatter.exposureTime(1.0))
        assertEquals("1", ExifValueFormatter.exposureTime(0.98))
        assertEquals("2.5", ExifValueFormatter.exposureTime(2.5))
        assertEquals("30", ExifValueFormatter.exposureTime(30.0))
    }

    @Test
    fun `invalid values are dropped`() {
        assertNull(ExifValueFormatter.exposureTime(0.0))
        assertNull(ExifValueFormatter.exposureTime(-1.0))
        assertNull(ExifValueFormatter.exposureTime(Double.NaN))
    }
}
