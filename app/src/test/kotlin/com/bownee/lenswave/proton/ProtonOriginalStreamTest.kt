package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ProtonOriginalStreamTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `progress uses actual written bytes and sdk total`() {
        val stream = ProtonOriginalStream(temporaryFolder.newFile())

        stream.updateProgress(downloadedBytes = 0L, totalBytes = 1_000L)
        stream.bytesWritten(250)

        assertEquals(
            ProtonOriginalDownloadProgress(250L, 1_000L, complete = false),
            stream.progress.value,
        )
        assertEquals(25, stream.progress.value.percent)
    }

    @Test
    fun `reader waits for bytes and resumes as soon as they are written`() {
        val stream = ProtonOriginalStream(temporaryFolder.newFile())
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val readState =
                executor.submit<ProtonOriginalReadState> {
                    started.countDown()
                    stream.awaitReadable(0L)
                }
            assertTrue(started.await(1L, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                readState.get(50L, TimeUnit.MILLISECONDS)
            }

            stream.bytesWritten(64)

            assertEquals(
                ProtonOriginalReadState(64L, complete = false),
                readState.get(1L, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a decrypt reports totals and completion moves the stream to the committed file`() {
        val growing = temporaryFolder.newFile("video.image.part")
        val committed = File(temporaryFolder.root, "video.image")
        val stream = ProtonOriginalStream(growing)

        growing.writeBytes(ByteArray(1_000))
        stream.availableBytes(1_000L)
        // A total that lags what is already known changes nothing.
        stream.availableBytes(500L)
        assertEquals(ProtonOriginalReadState(1_000L, complete = false), stream.awaitReadable(0L))
        assertEquals(growing, stream.file)

        growing.appendBytes(ByteArray(24))
        assertTrue(growing.renameTo(committed))
        stream.complete(committed)

        assertEquals(committed, stream.file)
        assertEquals(committed, stream.awaitCompletion())
        assertEquals(ProtonOriginalReadState(1_024L, complete = true), stream.awaitReadable(0L))
        assertEquals(ProtonOriginalDownloadProgress(1_024L, 1_024L, complete = true), stream.progress.value)
    }

    @Test
    fun `a reader blocked beyond the downloaded prefix is visible until it is served`() {
        val stream = ProtonOriginalStream(temporaryFolder.newFile())
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.bytesWritten(100)
            assertFalse(stream.waitingForBytes.value)
            // Reading inside the prefix never blocks or reports waiting.
            assertEquals(ProtonOriginalReadState(100L, complete = false), stream.awaitReadable(50L))
            assertFalse(stream.waitingForBytes.value)

            val readState = executor.submit<ProtonOriginalReadState> { stream.awaitReadable(5_000L) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
            while (!stream.waitingForBytes.value && System.nanoTime() < deadline) Thread.yield()
            assertTrue(stream.waitingForBytes.value)

            stream.availableBytes(6_000L)

            assertEquals(ProtonOriginalReadState(6_000L, complete = false), readState.get(1L, TimeUnit.SECONDS))
            assertFalse(stream.waitingForBytes.value)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a bounded wait returns on its own once the timeout passes`() {
        val stream = ProtonOriginalStream(temporaryFolder.newFile())
        val started = System.nanoTime()

        stream.awaitChange(30L)

        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("waited $waitedMillis ms", waitedMillis in 20L..2_000L)
        // A complete stream never waits: there is nothing left to arrive.
        stream.complete()
        val again = System.nanoTime()
        stream.awaitChange(5_000L)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 1_000L)
    }

    @Test
    fun `failure unblocks a waiting reader`() {
        val stream = ProtonOriginalStream(temporaryFolder.newFile())
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val readState =
                executor.submit<ProtonOriginalReadState> {
                    started.countDown()
                    stream.awaitReadable(0L)
                }
            assertTrue(started.await(1L, TimeUnit.SECONDS))

            stream.fail(IllegalStateException("network stopped"))

            val failure =
                assertThrows(ExecutionException::class.java) {
                    readState.get(1L, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is IOException)
            assertTrue(failure.cause?.cause is IllegalStateException)
            assertFalse(stream.progress.value.complete)
        } finally {
            executor.shutdownNow()
        }
    }
}
