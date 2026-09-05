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
 *
 * Epochs are kept per [owner] (the user) so that a sign-out or an account switch can drop them
 * with the user's files ([forget], [retainOnly]): a removal only ever orders work of the same
 * account, and the map would otherwise grow by every photo ever trashed for as long as the
 * process lives.
 */
internal class ProtonRemovalEpochs {
    private val epochs = ConcurrentHashMap<Key, Long>()
    private val locks = Array(LOCK_COUNT) { Any() }

    /** The epoch [key] of [owner] is in now; a decrypt or download captures it before it starts. */
    fun current(
        owner: String,
        key: String,
    ): Long = epochs[Key(owner, key)] ?: 0L

    /** Runs [remove] with the epoch advanced, so every commit that started before it is refused. */
    fun <T> remove(
        owner: String,
        key: String,
        remove: () -> T,
    ): T {
        val epochKey = Key(owner, key)
        return synchronized(lock(epochKey)) {
            epochs.merge(epochKey, 1L, Long::plus)
            remove()
        }
    }

    /**
     * Runs [commit] while [key] of [owner] is still in [startedIn] and returns true; false,
     * without running it, when a removal came between the start and now.
     */
    fun commitIf(
        owner: String,
        key: String,
        startedIn: Long,
        commit: () -> Unit,
    ): Boolean {
        val epochKey = Key(owner, key)
        return synchronized(lock(epochKey)) {
            if ((epochs[epochKey] ?: 0L) != startedIn) return false
            commit()
            true
        }
    }

    /** Drops every epoch of [owner]; for the user whose files are being erased. */
    fun forget(owner: String) {
        epochs.keys.removeAll { key -> key.owner == owner }
    }

    /** Drops the epochs of every owner but [owner]; for the account transition's sweep of the others. */
    fun retainOnly(owner: String?) {
        epochs.keys.removeAll { key -> key.owner != owner }
    }

    private fun lock(key: Key): Any = locks[Math.floorMod(key.hashCode(), LOCK_COUNT)]

    private data class Key(
        val owner: String,
        val key: String,
    )

    private companion object {
        const val LOCK_COUNT = 64
    }
}

/** A download or decrypt finished after its photo was removed; nothing was committed and the caller's temporary is gone. */
internal class ProtonOriginalRemovedException :
    IllegalStateException("The original was removed while it was being prepared")
