package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ProtonRenditionReadFailuresTest {
    private val reported = mutableListOf<Throwable>()
    private val failures = ProtonRenditionReadFailures { error -> reported += error }

    @Test
    fun `only the first failure of a streak is reported`() {
        val first = IOException("disk busy")

        failures.report(first)
        failures.report(IOException("still busy"))
        failures.report(IOException("and again"))

        assertEquals(listOf<Throwable>(first), reported)
    }

    @Test
    fun `a successful read ends the streak so the next failure is reported again`() {
        val first = IOException("first streak")
        val second = IOException("second streak")

        failures.report(first)
        failures.recovered()
        failures.report(second)
        failures.report(IOException("quiet"))

        assertEquals(listOf<Throwable>(first, second), reported)
    }
}
