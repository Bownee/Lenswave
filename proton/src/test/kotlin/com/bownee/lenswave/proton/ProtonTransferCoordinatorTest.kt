package com.bownee.lenswave.proton

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonTransferCoordinatorTest {
    @Test
    fun `foreground transfer prevents new background work from starting`() =
        runBlocking {
            val coordinator = ProtonTransferCoordinator()
            val foregroundStarted = CompletableDeferred<Unit>()
            val releaseForeground = CompletableDeferred<Unit>()
            val foreground =
                async {
                    coordinator.withForegroundTransfer {
                        foregroundStarted.complete(Unit)
                        releaseForeground.await()
                    }
                }
            foregroundStarted.await()

            var backgroundStarted = false
            val background =
                async {
                    coordinator.withBackgroundTransfer { backgroundStarted = true }
                }
            delay(50L)
            assertFalse(backgroundStarted)

            releaseForeground.complete(Unit)
            withTimeout(1_000L) {
                foreground.await()
                background.await()
            }
            assertTrue(backgroundStarted)
        }

    @Test
    fun `foreground transfer starts without waiting for an existing background request`() =
        runBlocking {
            val coordinator = ProtonTransferCoordinator()
            val backgroundStarted = CompletableDeferred<Unit>()
            val releaseBackground = CompletableDeferred<Unit>()
            val background =
                async {
                    coordinator.withBackgroundTransfer {
                        backgroundStarted.complete(Unit)
                        releaseBackground.await()
                    }
                }
            backgroundStarted.await()

            var foregroundStarted = false
            withTimeout(1_000L) {
                coordinator.withForegroundTransfer { foregroundStarted = true }
            }

            assertTrue(foregroundStarted)
            releaseBackground.complete(Unit)
            background.await()
        }

    @Test
    fun `cancelling foreground transfer releases background work`() =
        runBlocking {
            val coordinator = ProtonTransferCoordinator()
            val foregroundStarted = CompletableDeferred<Unit>()
            val foreground =
                async {
                    coordinator.withForegroundTransfer {
                        foregroundStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            foregroundStarted.await()

            foreground.cancelAndJoin()

            var backgroundStarted = false
            withTimeout(1_000L) {
                coordinator.withBackgroundTransfer { backgroundStarted = true }
            }
            assertTrue(backgroundStarted)
        }

    @Test
    fun `the background side reports a foreground transfer that outlasts its patience`() =
        runBlocking {
            val coordinator = ProtonTransferCoordinator()
            val foregroundStarted = CompletableDeferred<Unit>()
            val releaseForeground = CompletableDeferred<Unit>()
            val foreground =
                async {
                    coordinator.withForegroundTransfer {
                        foregroundStarted.complete(Unit)
                        releaseForeground.await()
                    }
                }
            foregroundStarted.await()

            assertFalse(coordinator.awaitNoForegroundTransfer(timeoutMillis = 20L))

            releaseForeground.complete(Unit)
            withTimeout(1_000L) {
                foreground.await()
                assertTrue(coordinator.awaitNoForegroundTransfer(timeoutMillis = 20L))
            }
        }

    @Test
    fun `a pass already under way goes ahead after a bounded wait`() =
        runBlocking {
            val coordinator = ProtonTransferCoordinator()
            val foregroundStarted = CompletableDeferred<Unit>()
            val releaseForeground = CompletableDeferred<Unit>()
            val foreground =
                async {
                    coordinator.withForegroundTransfer {
                        foregroundStarted.complete(Unit)
                        releaseForeground.await()
                    }
                }
            foregroundStarted.await()

            var backgroundStarted = false
            withTimeout(1_000L) {
                coordinator.withBackgroundTransfer(maxWaitMillis = 20L) { backgroundStarted = true }
            }
            assertTrue(backgroundStarted)

            releaseForeground.complete(Unit)
            foreground.await()
        }
}
