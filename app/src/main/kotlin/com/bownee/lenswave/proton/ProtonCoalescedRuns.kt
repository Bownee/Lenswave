package com.bownee.lenswave.proton

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One run per key at a time, with later callers waiting on the run in flight instead of
 * starting their own. A sync that enumerates a whole library must not be held under a lock
 * (a trash or a favourite would wait on the network), but two of them for the same account
 * enumerating side by side would double the traffic only to commit the same listing twice;
 * the second caller here gets the first one's outcome.
 */
internal class ProtonCoalescedRuns<K> {
    private class Run(
        val forced: Boolean,
        val done: CompletableDeferred<Unit>,
    )

    private val inFlight = HashMap<K, Run>()

    /**
     * Runs [block] for [key], or waits for the run already in flight when that run serves this
     * call: a forced run serves every caller, an unforced one serves only unforced callers, so
     * a caller who asked for a forced refresh waits for the unforced run to end and then runs
     * its own. A run that was cancelled or failed serves nobody; its waiters run themselves.
     */
    suspend fun run(
        key: K,
        forced: Boolean,
        block: suspend () -> Unit,
    ) {
        while (true) {
            val existing: Run?
            val own: Run
            synchronized(inFlight) {
                existing = inFlight[key]
                own = existing ?: Run(forced, CompletableDeferred()).also { inFlight[key] = it }
            }
            if (existing == null) {
                runAsOwner(key, own, block)
                return
            }
            val served =
                try {
                    existing.done.await()
                    existing.forced || !forced
                } catch (error: CancellationException) {
                    // The owner was cancelled, not this caller; run in its place.
                    currentCoroutineContext().ensureActive()
                    false
                } catch (_: Throwable) {
                    false
                }
            if (served) return
        }
    }

    private suspend fun runAsOwner(
        key: K,
        run: Run,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            run.done.complete(Unit)
        } catch (error: Throwable) {
            run.done.completeExceptionally(error)
            throw error
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
    }
}
