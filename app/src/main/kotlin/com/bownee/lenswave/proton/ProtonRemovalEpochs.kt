package com.bownee.lenswave.proton

import java.util.concurrent.ConcurrentHashMap

/**
 * Orders the commits of decrypts and downloads against the removal of their node.
 *
 * A decrypt reads an encrypted original into a private temporary file and renames it to the
 * shared plaintext path at the end; a download does the same with the file it fetched and the
 * encrypted original it encrypts. Either may have started before the photo was trashed and
 * finish after [remove] deleted both files, which used to bring the plaintext (or the original)
 * back. Every node carries an epoch that a removal advances under the node's lock; a commit
 * runs under the same lock only while the epoch is still the one its work started in, and a
 * commit refused this way leaves nothing behind but its own temporary, which the caller drops.
 */
internal class ProtonRemovalEpochs {
    private val epochs = ConcurrentHashMap<String, Long>()
    private val locks = Array(LOCK_COUNT) { Any() }

    /** The epoch [key] is in now; a decrypt or download captures it before it starts. */
    fun current(key: String): Long = epochs[key] ?: 0L

    /** Runs [remove] with [key]'s epoch advanced, so every commit that started before it is refused. */
    fun <T> remove(
        key: String,
        remove: () -> T,
    ): T =
        synchronized(lock(key)) {
            epochs.merge(key, 1L, Long::plus)
            remove()
        }

    /**
     * Runs [commit] while [key] is still in [startedIn] and returns true; false, without running
     * it, when a removal came between the start and now.
     */
    fun commitIf(
        key: String,
        startedIn: Long,
        commit: () -> Unit,
    ): Boolean =
        synchronized(lock(key)) {
            if (current(key) != startedIn) return false
            commit()
            true
        }

    private fun lock(key: String): Any = locks[Math.floorMod(key.hashCode(), LOCK_COUNT)]

    private companion object {
        const val LOCK_COUNT = 64
    }
}

/** A download or decrypt finished after its photo was removed; nothing was committed and the caller's temporary is gone. */
internal class ProtonOriginalRemovedException :
    IllegalStateException("The original was removed while it was being prepared")
