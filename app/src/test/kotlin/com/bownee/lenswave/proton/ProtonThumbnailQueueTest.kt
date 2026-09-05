package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonThumbnailQueueTest {
    @Test
    fun newestCaptureTimeIsAlwaysChosenFirst() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(
                USER_ID,
                "timeline",
                listOf(candidate("old", 100), candidate("newest", 300), candidate("middle", 200)),
            )

            assertEquals(
                listOf("newest", "middle", "old"),
                queue.claimReady(USER_ID, limit = 3).map(ProtonThumbnailQueueEntry::nodeUid),
            )
        }

    @Test
    fun claimingFewerThanPendingStillTakesTheNewestOnes() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(
                USER_ID,
                "timeline",
                (1..50L).map { captureTime -> candidate("photo-$captureTime", captureTime) }.shuffled(),
            )

            assertEquals(
                listOf("photo-50", "photo-49", "photo-48"),
                queue.claimReady(USER_ID, limit = 3).map(ProtonThumbnailQueueEntry::nodeUid),
            )
        }

    @Test
    fun sourceReplacementReordersExistingEntriesByCaptureTime() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(
                USER_ID,
                "timeline",
                listOf(candidate("previously-newest", 300), candidate("updated", 100)),
            )

            queue.replaceSource(
                USER_ID,
                "timeline",
                listOf(candidate("previously-newest", 300), candidate("updated", 400)),
            )

            assertEquals("updated", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        }

    @Test
    fun replacingOneSourcePreservesItemsReferencedByAnotherSource() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())

            queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))
            queue.replaceSource(USER_ID, "album", candidates("b", "c"))
            queue.replaceSource(USER_ID, "timeline", candidates("b"))
            queue.flush(USER_ID)

            assertEquals(
                setOf("b", "c"),
                store.entries
                    .getValue(USER_ID)
                    .map { it.nodeUid }
                    .toSet(),
            )
            assertEquals(setOf("timeline", "album"), entry(store, "b").sources)
        }

    @Test
    fun removingNewerSourceRestoresRemainingSourcesCaptureTime() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", listOf(candidate("shared", 100), candidate("other", 200)))
            queue.replaceSource(USER_ID, "album", listOf(candidate("shared", 300)))
            val initiallyClaimed = queue.claimReady(USER_ID, limit = 1)
            assertEquals("shared", initiallyClaimed.single().nodeUid)
            queue.release(USER_ID, initiallyClaimed.map(ProtonThumbnailQueueEntry::nodeUid))

            queue.replaceSource(USER_ID, "album", emptyList())

            assertEquals("other", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        }

    @Test
    fun aFailedItemIsDeferredWithoutBlockingTheNextNewestThumbnail() =
        runTest {
            val clock = FakeClock()
            val queue = queue(FakeStore(), clock)
            queue.replaceSource(
                USER_ID,
                "timeline",
                listOf(candidate("newest", 200), candidate("older", 100)),
            )

            assertEquals("newest", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
            queue.settle(USER_ID, emptySet(), setOf("newest"))
            assertEquals("older", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
            queue.settle(USER_ID, setOf("older"), emptySet())
            assertEquals(emptyList<ProtonThumbnailQueueEntry>(), queue.claimReady(USER_ID, limit = 1))

            clock.value += 30_000L
            assertEquals("newest", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        }

    @Test
    fun invalidatedThumbnailIsRestoredForImmediateDownloadAtItsCaptureTime() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", listOf(candidate("photo", 200)))
            queue.settle(USER_ID, setOf("photo"), emptySet())

            queue.retryNow(USER_ID, candidate("photo", 200), setOf("timeline", "album:one"))

            val restored = queue.claimReady(USER_ID, limit = 1).single()
            assertEquals("photo", restored.nodeUid)
            assertEquals(200L, restored.captureTimeEpochSeconds)
            assertEquals(setOf("timeline", "album:one"), restored.sources)
        }

    @Test
    fun claimedBatchesDoNotDuplicateInFlightWork() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("a", "b", "c"))

            val first = queue.claimReady(USER_ID, limit = 2)
            val second = queue.claimReady(USER_ID, limit = 2)

            assertEquals(2, first.size)
            assertEquals(1, second.size)
            assertEquals(setOf("a", "b", "c"), (first + second).map { it.nodeUid }.toSet())
        }

    @Test
    fun releasedClaimsCanBeSelectedAgain() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("photo"))
            val claimed = queue.claimReady(USER_ID, limit = 1)

            queue.release(USER_ID, claimed.map { it.nodeUid })

            assertEquals("photo", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        }

    @Test
    fun settlingABatchIsWrittenOnceAfterTheFlushDelay() =
        runTest {
            val store = FakeStore()
            val clock = FakeClock()
            val queue = queue(store, clock)
            queue.replaceSource(USER_ID, "timeline", candidates("success", "failure", "later"))
            queue.claimReady(USER_ID, limit = 3)
            val writesBeforeSettlement = store.writeCount

            val completed =
                queue.settle(
                    USER_ID,
                    successfulNodeUids = setOf("success"),
                    failedNodeUids = setOf("failure"),
                )
            queue.settle(USER_ID, successfulNodeUids = setOf("later"), failedNodeUids = emptySet())

            assertEquals(listOf("success"), completed.map { it.nodeUid })
            assertEquals(writesBeforeSettlement, store.writeCount)
            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            assertEquals(writesBeforeSettlement + 1, store.writeCount)
            assertEquals(listOf("failure"), store.entries.getValue(USER_ID).map { it.nodeUid })
            assertEquals(emptyList<ProtonThumbnailQueueEntry>(), queue.claimReady(USER_ID, limit = 1))
            clock.value += 30_000L
            assertEquals("failure", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
        }

    @Test
    fun flushingWritesPendingChangesAtOnceAndCancelsTheScheduledWrite() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))
            queue.claimReady(USER_ID, limit = 2)
            val writesBeforeSettlement = store.writeCount
            queue.settle(USER_ID, setOf("a"), emptySet())

            queue.flush(USER_ID)

            assertEquals(writesBeforeSettlement + 1, store.writeCount)
            assertEquals(listOf("b"), store.entries.getValue(USER_ID).map { it.nodeUid })
            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            assertEquals(writesBeforeSettlement + 1, store.writeCount)
            queue.flush(USER_ID)
            assertEquals(writesBeforeSettlement + 1, store.writeCount)
        }

    @Test
    fun manySettlesBringTheWriteForward() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            val nodeUids = (1..ProtonQueueFlushPolicy.MAX_UNFLUSHED_CHANGES).map { "photo-$it" }
            queue.replaceSource(USER_ID, "timeline", candidates(*nodeUids.toTypedArray()))
            queue.claimReady(USER_ID, limit = nodeUids.size)
            val writesBeforeSettlement = store.writeCount

            nodeUids.forEach { nodeUid -> queue.settle(USER_ID, setOf(nodeUid), emptySet()) }
            runCurrent()

            assertEquals(writesBeforeSettlement + 1, store.writeCount)
            assertEquals(emptyList<ProtonThumbnailQueueEntry>(), store.entries.getValue(USER_ID))
        }

    @Test
    fun aFailedWriteIsRetriedAndReportedOnce() =
        runTest {
            val store = FakeStore()
            val failures = mutableListOf<Throwable>()
            val queue =
                ProtonThumbnailQueue(store, FakeClock(), ProtonQueueName.THUMBNAILS, backgroundScope, failures::add)
            queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))
            queue.claimReady(USER_ID, limit = 2)
            queue.flush(USER_ID)
            store.failWrites = true
            queue.settle(USER_ID, setOf("a"), emptySet())

            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            assertEquals(1, failures.size)
            advanceTimeBy(ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(1))
            runCurrent()
            assertEquals(1, failures.size)
            assertEquals(listOf("a", "b"), store.entries.getValue(USER_ID).map { it.nodeUid })

            store.failWrites = false
            advanceTimeBy(ProtonQueueFlushPolicy.retryDelayAfterFailedWrite(2))
            runCurrent()

            assertEquals(listOf("b"), store.entries.getValue(USER_ID).map { it.nodeUid })
            assertEquals(1, failures.size)
            queue.flush(USER_ID)
            assertEquals(1, failures.size)
        }

    @Test
    fun aWriteThatKeepsFailingIsGivenUpOnUntilTheNextChange() =
        runTest {
            val store = FakeStore()
            val failures = mutableListOf<Throwable>()
            val queue =
                ProtonThumbnailQueue(store, FakeClock(), ProtonQueueName.THUMBNAILS, backgroundScope, failures::add)
            queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))
            queue.claimReady(USER_ID, limit = 2)
            queue.flush(USER_ID)
            store.failWrites = true
            val attemptsBefore = store.writeAttempts
            queue.settle(USER_ID, setOf("a"), emptySet())

            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            // The first write and every retry the chain allows; then it stops asking.
            advanceTimeBy(
                ProtonQueueFlushPolicy.MAX_WRITE_RETRY_DELAY_MILLIS * (ProtonQueueFlushPolicy.MAX_WRITE_RETRIES + 5),
            )
            runCurrent()
            assertEquals(1 + ProtonQueueFlushPolicy.MAX_WRITE_RETRIES, store.writeAttempts - attemptsBefore)
            assertEquals(1, failures.size)

            // The next change starts a fresh chain, and a disk that works again gets the write.
            store.failWrites = false
            queue.settle(USER_ID, setOf("b"), emptySet())
            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            assertEquals(emptyList<ProtonThumbnailQueueEntry>(), store.entries.getValue(USER_ID))
        }

    @Test
    fun forgettingAUserDiscardsItsPendingWrite() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("a"))
            queue.claimReady(USER_ID, limit = 1)
            val writesBeforeSettlement = store.writeCount
            queue.settle(USER_ID, setOf("a"), emptySet())

            queue.forget(USER_ID)
            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            queue.flush(USER_ID)

            assertEquals(writesBeforeSettlement, store.writeCount)
        }

    @Test
    fun settlingUsesSourcesAddedWhileAThumbnailWasInFlight() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("photo"))
            queue.claimReady(USER_ID, limit = 1)
            queue.replaceSource(USER_ID, "album:one", candidates("photo"))

            val completed = queue.settle(USER_ID, setOf("photo"), emptySet())

            assertEquals(setOf("timeline", "album:one"), completed.single().sources)
        }

    @Test
    fun pendingQueueSurvivesCreatingANewQueueInstance() =
        runTest {
            val store = FakeStore()
            queue(store, FakeClock()).apply {
                replaceSource(USER_ID, "timeline", candidates("photo"))
                flush(USER_ID)
            }

            val restored = queue(store, FakeClock())

            assertEquals("photo", restored.claimReady(USER_ID, limit = 1).single().nodeUid)
        }

    @Test
    fun pendingCountIncludesClaimedAndDelayedWorkUntilItSettles() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("claimed", "delayed"))
            queue.claimReady(USER_ID, limit = 1)
            queue.settle(USER_ID, emptySet(), setOf("delayed"))

            assertEquals(2, queue.pendingCount(USER_ID))

            queue.settle(USER_ID, setOf("claimed"), emptySet())
            assertEquals(1, queue.pendingCount(USER_ID))
        }

    @Test
    fun deletedAlbumsNoLongerKeepTheirThumbnailWork() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "album:kept", candidates("kept-photo"))
            queue.replaceSource(USER_ID, "album:deleted", candidates("deleted-photo"))

            queue.replaceSources(USER_ID, emptyMap(), retainedAlbumNodeUids = listOf("kept"))
            queue.flush(USER_ID)

            assertEquals(listOf("kept-photo"), store.entries.getValue(USER_ID).map { it.nodeUid })
        }

    @Test
    fun replacingAlbumCoversPreservesTimelineWork() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("timeline-photo"))
            queue.replaceSource(USER_ID, "album-covers", candidates("old-cover"))

            queue.replaceSources(
                USER_ID,
                mapOf("album-covers" to candidates("new-cover")),
                retainedAlbumNodeUids = emptyList(),
            )
            queue.flush(USER_ID)

            assertEquals(
                setOf("timeline-photo", "new-cover"),
                store.entries
                    .getValue(USER_ID)
                    .map { it.nodeUid }
                    .toSet(),
            )
        }

    @Test
    fun aSourceReplacementIsWrittenAfterTheFlushDelayNotAtOnce() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())

            queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))

            assertEquals(0, store.writeCount)
            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            assertEquals(1, store.writeCount)
            assertEquals(
                setOf("a", "b"),
                store.entries
                    .getValue(USER_ID)
                    .map { it.nodeUid }
                    .toSet(),
            )
        }

    @Test
    fun anUnchangedSourceReplacementPerformsNoWrite() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "timeline", listOf(candidate("a", 100), candidate("b", 200)))
            queue.replaceSource(USER_ID, "album:one", listOf(candidate("b", 300), candidate("c", 400)))
            queue.flush(USER_ID)
            val writesBefore = store.writeCount

            queue.replaceSource(USER_ID, "timeline", listOf(candidate("b", 200), candidate("a", 100)))
            queue.replaceSources(
                USER_ID,
                mapOf("album:one" to listOf(candidate("c", 400), candidate("b", 300))),
                retainedAlbumNodeUids = listOf("one"),
            )
            queue.replaceSources(USER_ID, emptyMap(), retainedAlbumNodeUids = listOf("one", "other"))

            advanceTimeBy(ProtonQueueFlushPolicy.FLUSH_DELAY_MILLIS)
            runCurrent()
            queue.flush(USER_ID)
            assertEquals(writesBefore, store.writeCount)
            assertEquals(setOf("a", "b", "c"), queue.claimReady(USER_ID, limit = 3).map { it.nodeUid }.toSet())
        }

    @Test
    fun aChangedCaptureTimeOrSourceSetIsStillWritten() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "timeline", listOf(candidate("a", 100), candidate("b", 200)))
            queue.flush(USER_ID)
            val writesBefore = store.writeCount

            queue.replaceSource(USER_ID, "timeline", listOf(candidate("a", 100), candidate("b", 250)))
            queue.flush(USER_ID)
            assertEquals(writesBefore + 1, store.writeCount)
            assertEquals(250L, entry(store, "b").captureTimeEpochSeconds)

            queue.replaceSource(USER_ID, "timeline", listOf(candidate("a", 100)))
            queue.flush(USER_ID)
            assertEquals(writesBefore + 2, store.writeCount)
            assertEquals(listOf("a"), store.entries.getValue(USER_ID).map { it.nodeUid })
        }

    @Test
    fun previewQueuePersistsSeparatelyFromTheThumbnailQueue() =
        runTest {
            val store = FakeStore()
            val thumbnails = queue(store, FakeClock(), ProtonQueueName.THUMBNAILS)
            val previews = queue(store, FakeClock(), ProtonQueueName.PREVIEWS)

            thumbnails.replaceSource(USER_ID, "timeline", candidates("thumb"))
            previews.replaceSource(USER_ID, "timeline-previews", candidates("preview"))
            thumbnails.flush(USER_ID)
            previews.flush(USER_ID)

            assertEquals(listOf("thumb"), store.entries.getValue(USER_ID).map { it.nodeUid })
            assertEquals(
                listOf("preview"),
                store.previewEntries.getValue(USER_ID).map { it.nodeUid },
            )
            assertEquals("thumb", thumbnails.claimReady(USER_ID, limit = 5).single().nodeUid)
            assertEquals("preview", previews.claimReady(USER_ID, limit = 5).single().nodeUid)
        }

    @Test
    fun theDefaultQueueIsTheThumbnailQueue() =
        runTest {
            val store = FakeStore()
            queue(store, FakeClock()).apply {
                replaceSource(USER_ID, "timeline", candidates("photo"))
                flush(USER_ID)
            }

            assertEquals(listOf("photo"), store.entries.getValue(USER_ID).map { it.nodeUid })
            assertEquals(null, store.previewEntries[USER_ID])
        }

    @Test
    fun replacingAnUnrelatedSourceLeavesOtherEntriesUntouched() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("a", "b"))
            val before = queue.claimReady(USER_ID, limit = 2)
            queue.release(USER_ID, before.map { it.nodeUid })

            queue.replaceSource(USER_ID, "album:one", candidates("c"))

            val after = queue.claimReady(USER_ID, limit = 3).filter { it.nodeUid != "c" }
            assertEquals(before, after)
            before.zip(after).forEach { (old, new) -> assertTrue(old === new) }
        }

    @Test
    fun theQueueFileIsReadOnceAndAUserForgottenDuringTheReadStaysEmpty() =
        runTest {
            val store = FakeStore()
            store.entries[USER_ID] = listOf(ProtonThumbnailQueueEntry("stored", mapOf("timeline" to 1L)))
            val queue = queue(store, FakeClock())

            assertEquals(1, queue.pendingCount(USER_ID))
            assertEquals(1, queue.claimReady(USER_ID, limit = 1).size)
            assertEquals(1, store.readCount)

            queue.forget(USER_ID)
            store.onRead = { kotlinx.coroutines.runBlocking { queue.forget(USER_ID) } }
            assertEquals(0, queue.pendingCount(USER_ID))
            assertEquals(2, store.readCount)
        }

    @Test
    fun aRenditionProtonDoesNotHaveIsDroppedAtOnce() =
        runTest {
            val store = FakeStore()
            val queue = queue(store, FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("missing", "flaky", "fine"))
            queue.claimReady(USER_ID, limit = 3)

            queue.settle(
                USER_ID,
                setOf("fine"),
                mapOf("missing" to ThumbnailFailureKind.NOT_FOUND, "flaky" to ThumbnailFailureKind.UNANSWERED),
            )
            queue.flush(USER_ID)

            assertEquals(listOf("flaky"), store.entries.getValue(USER_ID).map { it.nodeUid })
            assertEquals(1, entry(store, "flaky").retryCount)
            assertEquals(emptyList<ProtonThumbnailQueueEntry>(), queue.claimReady(USER_ID, limit = 3))
        }

    @Test
    fun aConnectionFailureCostsNoRetryStepAndPausesBriefly() =
        runTest {
            val store = FakeStore()
            val clock = FakeClock()
            val queue = queue(store, clock)
            queue.replaceSource(USER_ID, "timeline", candidates("offline", "slow"))
            // Two ordinary failures first, so the retry count and its backoff are visible.
            repeat(2) {
                queue.claimReady(USER_ID, limit = 2)
                queue.settle(USER_ID, emptySet(), setOf("offline", "slow"))
                clock.value += 60L * 60L * 1_000L
            }
            queue.claimReady(USER_ID, limit = 2)

            queue.settle(
                USER_ID,
                emptySet(),
                mapOf("offline" to ThumbnailFailureKind.TRANSIENT_NETWORK, "slow" to ThumbnailFailureKind.OTHER),
            )
            queue.flush(USER_ID)

            assertEquals(2, entry(store, "offline").retryCount)
            assertEquals(3, entry(store, "slow").retryCount)
            assertEquals(clock.value + ProtonThumbnailQueue.NETWORK_RETRY_MILLIS, entry(store, "offline").retryAtMillis)
            assertEquals(ProtonThumbnailQueue.NETWORK_RETRY_MILLIS, queue.retryDelayMillis(USER_ID))
            assertTrue(queue.claimReady(USER_ID, limit = 2).isEmpty())
            clock.value += ProtonThumbnailQueue.NETWORK_RETRY_MILLIS
            assertEquals(listOf("offline"), queue.claimReady(USER_ID, limit = 2).map { it.nodeUid })
        }

    @Test
    fun connectionFailuresNeverDropAnEntry() =
        runTest {
            val clock = FakeClock()
            val queue = queue(FakeStore(), clock)
            queue.replaceSource(USER_ID, "timeline", candidates("offline"))
            repeat(ProtonThumbnailQueue.MAX_RETRY_COUNT * 2) {
                assertEquals("offline", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
                queue.settle(USER_ID, emptySet(), mapOf("offline" to ThumbnailFailureKind.TRANSIENT_NETWORK))
                clock.value += ProtonThumbnailQueue.NETWORK_RETRY_MILLIS
            }

            assertEquals(1, queue.pendingCount(USER_ID))
        }

    @Test
    fun anEntryIsDroppedAfterTheLastAllowedRetry() =
        runTest {
            val store = FakeStore()
            val clock = FakeClock()
            val queue = queue(store, clock)
            queue.replaceSource(USER_ID, "timeline", candidates("stubborn"))

            repeat(ProtonThumbnailQueue.MAX_RETRY_COUNT - 1) {
                assertEquals("stubborn", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
                queue.settle(USER_ID, emptySet(), setOf("stubborn"))
                clock.value += 60L * 60L * 1_000L
            }
            assertEquals(1, queue.pendingCount(USER_ID))

            queue.claimReady(USER_ID, limit = 1)
            queue.settle(USER_ID, emptySet(), setOf("stubborn"))
            queue.flush(USER_ID)

            assertEquals(0, queue.pendingCount(USER_ID))
            assertEquals(emptyList<ProtonThumbnailQueueEntry>(), store.entries.getValue(USER_ID))
        }

    @Test
    fun aDroppedEntryIsNotQueuedAgainByTheNextReconciliationForAWeek() =
        runTest {
            val clock = FakeClock()
            val queue = queue(FakeStore(), clock)
            queue.replaceSource(USER_ID, "timeline", candidates("missing", "fine"))
            queue.claimReady(USER_ID, limit = 2)
            queue.settle(USER_ID, setOf("fine"), mapOf("missing" to ThumbnailFailureKind.NOT_FOUND))
            assertEquals(0, queue.pendingCount(USER_ID))

            // Every sync re-adds each photo without a thumbnail; the dropped one stays out.
            queue.replaceSource(USER_ID, "timeline", candidates("missing", "fine"))
            queue.replaceSource(USER_ID, "album:one", candidates("missing"))
            assertEquals(listOf("fine"), queue.claimReady(USER_ID, limit = 2).map { it.nodeUid })

            clock.value += ProtonThumbnailQueue.SUPPRESSION_MILLIS
            queue.replaceSource(USER_ID, "timeline", candidates("missing"))
            assertEquals(1, queue.pendingCount(USER_ID))
        }

    @Test
    fun anEntryThatSpentItsRetriesIsSuppressedUntilAnExplicitAsk() =
        runTest {
            val clock = FakeClock()
            val queue = queue(FakeStore(), clock)
            queue.replaceSource(USER_ID, "timeline", candidates("stubborn"))
            repeat(ProtonThumbnailQueue.MAX_RETRY_COUNT) {
                clock.value += 60L * 60L * 1_000L
                queue.claimReady(USER_ID, limit = 1)
                queue.settle(USER_ID, emptySet(), setOf("stubborn"))
            }
            assertEquals(0, queue.pendingCount(USER_ID))

            queue.replaceSource(USER_ID, "timeline", candidates("stubborn"))
            assertEquals(0, queue.pendingCount(USER_ID))

            // The grid could not decode what is stored and asks outright: that outranks the drop.
            queue.retryNow(USER_ID, candidate("stubborn", 1), setOf("timeline"))
            assertEquals("stubborn", queue.claimReady(USER_ID, limit = 1).single().nodeUid)
            queue.release(USER_ID, listOf("stubborn"))
            queue.replaceSource(USER_ID, "timeline", candidates("stubborn"))
            assertEquals(1, queue.pendingCount(USER_ID))
        }

    @Test
    fun forgettingAUserForgetsItsSuppressions() =
        runTest {
            val queue = queue(FakeStore(), FakeClock())
            queue.replaceSource(USER_ID, "timeline", candidates("missing"))
            queue.claimReady(USER_ID, limit = 1)
            queue.settle(USER_ID, emptySet(), mapOf("missing" to ThumbnailFailureKind.NOT_FOUND))

            queue.forget(USER_ID)
            queue.replaceSource(USER_ID, "timeline", candidates("missing"))

            assertEquals(1, queue.pendingCount(USER_ID))
        }

    private fun TestScope.queue(
        store: FakeStore,
        clock: FakeClock,
        name: ProtonQueueName = ProtonQueueName.THUMBNAILS,
    ) = ProtonThumbnailQueue(store, clock, name, backgroundScope)

    private fun candidate(
        nodeUid: String,
        captureTimeEpochSeconds: Long,
    ) = ProtonThumbnailCandidate(nodeUid, captureTimeEpochSeconds)

    private fun candidates(vararg nodeUids: String): List<ProtonThumbnailCandidate> =
        nodeUids.mapIndexed { index, nodeUid -> candidate(nodeUid, nodeUids.size - index.toLong()) }

    private fun entry(
        store: FakeStore,
        nodeUid: String,
    ) = store.entries.getValue(USER_ID).single { it.nodeUid == nodeUid }

    private class FakeStore : ProtonThumbnailQueueStore {
        val entries = mutableMapOf<String, List<ProtonThumbnailQueueEntry>>()
        val previewEntries = mutableMapOf<String, List<ProtonThumbnailQueueEntry>>()
        var writeCount = 0

        /** Every call, the refused ones included; [writeCount] counts only the writes that landed. */
        var writeAttempts = 0
        var readCount = 0
        var failWrites = false
        var onRead: () -> Unit = {}

        override fun readQueue(
            userId: String,
            queue: ProtonQueueName,
        ): List<ProtonThumbnailQueueEntry> {
            readCount++
            onRead()
            return entriesFor(queue)[userId].orEmpty()
        }

        override fun writeQueue(
            userId: String,
            queue: ProtonQueueName,
            entries: List<ProtonThumbnailQueueEntry>,
        ) {
            writeAttempts++
            if (failWrites) throw java.io.IOException("disk full")
            writeCount++
            entriesFor(queue)[userId] = entries.toList()
        }

        private fun entriesFor(queue: ProtonQueueName) =
            when (queue) {
                ProtonQueueName.THUMBNAILS -> entries
                ProtonQueueName.PREVIEWS -> previewEntries
            }
    }

    private class FakeClock(
        var value: Long = 1_000L,
    ) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private companion object {
        const val USER_ID = "user"
    }
}
