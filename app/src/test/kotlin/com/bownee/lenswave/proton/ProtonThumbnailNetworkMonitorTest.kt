package com.bownee.lenswave.proton

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonThumbnailNetworkMonitorTest {
    private val source = FakeNetworkSource()

    @Test
    fun `an already validated network is answered at once`() =
        runTest {
            source.validatedUnmetered = true
            val monitor = ProtonThumbnailNetworkMonitor(source)

            assertTrue(monitor.awaitValidatedUnmeteredNetwork(timeoutMillis = 0L))
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `the callback is registered before the first read so no change is missed`() {
        ProtonThumbnailNetworkMonitor(source)

        assertEquals(listOf("observe", "read"), source.events)
    }

    @Test
    fun `no validated network within the timeout is a no`() =
        runTest {
            val monitor = ProtonThumbnailNetworkMonitor(source)

            assertFalse(monitor.awaitValidatedUnmeteredNetwork(timeoutMillis = 5_000L))
            assertEquals(5_000L, testScheduler.currentTime)
        }

    @Test
    fun `a network validated during the wait is waited for`() =
        runTest {
            val monitor = ProtonThumbnailNetworkMonitor(source)
            launch {
                delay(2_000L)
                source.change(true)
            }

            assertTrue(monitor.awaitValidatedUnmeteredNetwork(timeoutMillis = 5_000L))
            assertEquals(2_000L, testScheduler.currentTime)
        }

    @Test
    fun `a network reported lost is no longer available`() =
        runTest {
            source.validatedUnmetered = true
            val monitor = ProtonThumbnailNetworkMonitor(source)
            assertTrue(monitor.awaitValidatedUnmeteredNetwork(timeoutMillis = 0L))

            source.change(false)

            assertFalse(monitor.awaitValidatedUnmeteredNetwork(timeoutMillis = 1_000L))
            assertEquals(1_000L, testScheduler.currentTime)
        }

    @Test
    fun `closing the monitor closes its source`() {
        val monitor = ProtonThumbnailNetworkMonitor(source)

        monitor.close()

        assertTrue(source.closed)
    }

    private class FakeNetworkSource : ProtonThumbnailNetworkSource {
        var validatedUnmetered = false
        var closed = false
        val events = mutableListOf<String>()
        private var onChange: ((Boolean) -> Unit)? = null

        /** What the platform's callback would report. */
        fun change(validatedUnmetered: Boolean) {
            this.validatedUnmetered = validatedUnmetered
            checkNotNull(onChange) { "the monitor has not observed the source" }(validatedUnmetered)
        }

        override fun isValidatedUnmetered(): Boolean {
            events += "read"
            return validatedUnmetered
        }

        override fun observe(onChange: (validatedUnmetered: Boolean) -> Unit) {
            events += "observe"
            this.onChange = onChange
        }

        override fun close() {
            closed = true
        }
    }
}
