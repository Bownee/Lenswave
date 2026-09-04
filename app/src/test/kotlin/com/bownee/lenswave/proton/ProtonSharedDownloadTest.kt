package com.bownee.lenswave.proton

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSharedDownloadTest {
    @Test
    fun `the last waiter to leave takes the transfer down and later joiners are refused`() =
        runTest {
            val transfer = CompletableDeferred<String>()
            val shared = ProtonSharedDownload(transfer)
            assertTrue(shared.tryJoin())
            val waiter = async { shared.await() }
            testScheduler.runCurrent()

            waiter.cancel()
            testScheduler.runCurrent()

            assertTrue(transfer.isCancelled)
            assertTrue(shared.isAbandoned)
            assertFalse(shared.tryJoin())
        }

    @Test
    fun `a waiter that joined keeps the transfer alive when another leaves`() =
        runTest {
            val transfer = CompletableDeferred<String>()
            val shared = ProtonSharedDownload(transfer)
            assertTrue(shared.tryJoin())
            assertTrue(shared.tryJoin())
            val first = async { shared.await() }
            val second = async { shared.await() }
            testScheduler.runCurrent()

            first.cancel()
            testScheduler.runCurrent()
            assertFalse(transfer.isCancelled)
            transfer.complete("file")

            assertEquals("file", second.await())
            assertFalse(shared.isAbandoned)
        }

    @Test
    fun `a finished transfer keeps admitting joiners`() =
        runTest {
            val transfer = CompletableDeferred("file")
            val shared = ProtonSharedDownload(transfer)

            assertTrue(shared.tryJoin())
            assertEquals("file", shared.await())
            assertTrue(shared.tryJoin())
            assertEquals("file", shared.await())
            assertFalse(shared.isAbandoned)
        }

    @Test
    fun `forgetting cancels the transfer for every waiter`() =
        runTest {
            val transfer = CompletableDeferred<String>()
            val shared = ProtonSharedDownload(transfer)
            assertTrue(shared.tryJoin())
            val waiter = async { runCatching { shared.await() } }
            testScheduler.runCurrent()

            shared.forget()

            assertTrue(waiter.await().exceptionOrNull() is CancellationException)
            assertTrue(transfer.isCancelled)
        }
}
