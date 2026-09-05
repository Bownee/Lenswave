package com.bownee.lenswave.proton

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonCoalescedRunsTest {
    private val runs = ProtonCoalescedRuns<String>()
    private val events = mutableListOf<String>()
    private val gate = CompletableDeferred<Unit>()

    @Test
    fun `a second unforced caller waits for the run in flight instead of running its own`() =
        runTest {
            val first = launch { runs.run("user", forced = false) { held("first") } }
            val second = launch { runs.run("user", forced = false) { held("second") } }
            runCurrent()

            assertEquals(listOf("start:first"), events)
            assertFalse(second.isCompleted)
            gate.complete(Unit)
            first.join()
            second.join()

            assertEquals(listOf("start:first", "end:first"), events)
        }

    @Test
    fun `different keys run side by side`() =
        runTest {
            launch { runs.run("a", forced = false) { held("a") } }
            launch { runs.run("b", forced = false) { held("b") } }
            runCurrent()

            assertEquals(listOf("start:a", "start:b"), events)
            gate.complete(Unit)
        }

    @Test
    fun `a forced caller is not served by an unforced run and runs its own afterwards`() =
        runTest {
            val unforced = launch { runs.run("user", forced = false) { held("unforced") } }
            val forced = launch { runs.run("user", forced = true) { events += "forced" } }
            runCurrent()

            assertEquals(listOf("start:unforced"), events)
            gate.complete(Unit)
            unforced.join()
            forced.join()

            assertEquals(listOf("start:unforced", "end:unforced", "forced"), events)
        }

    @Test
    fun `a forced run serves an unforced caller`() =
        runTest {
            launch { runs.run("user", forced = true) { held("forced") } }
            val unforced = launch { runs.run("user", forced = false) { events += "unforced" } }
            runCurrent()
            gate.complete(Unit)
            unforced.join()

            assertEquals(listOf("start:forced", "end:forced"), events)
        }

    @Test
    fun `a waiter whose owner was cancelled runs in its place`() =
        runTest {
            val owner = launch { runs.run("user", forced = false) { held("owner") } }
            val waiter = launch { runs.run("user", forced = false) { events += "waiter" } }
            runCurrent()

            owner.cancel()
            waiter.join()

            assertEquals(listOf("start:owner", "waiter"), events)
        }

    @Test
    fun `a run that failed serves nobody and the next caller starts fresh`() =
        runTest {
            val owner =
                launch {
                    runCatching {
                        runs.run("user", forced = false) {
                            gate.await()
                            error("boom")
                        }
                    }
                }
            val waiter = launch { runs.run("user", forced = false) { events += "waiter" } }
            runCurrent()
            gate.complete(Unit)
            owner.join()
            waiter.join()

            assertEquals(listOf("waiter"), events)
            runs.run("user", forced = false) { events += "later" }
            assertTrue(events.last() == "later")
        }

    private suspend fun held(name: String) {
        events += "start:$name"
        gate.await()
        events += "end:$name"
    }
}
