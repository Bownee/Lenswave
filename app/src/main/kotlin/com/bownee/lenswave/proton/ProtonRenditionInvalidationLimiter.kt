package com.bownee.lenswave.proton

/**
 * Bounds how often one stored rendition that fails to decode is dropped and queued again.
 *
 * A preview the viewer cannot decode is deleted and queued at the front, which lifts the queue's
 * suppression; when Proton keeps serving the same undecodable bytes, every viewer open of that
 * photo downloaded, stored, failed and re-queued it, for good. The queue cannot count this: each
 * successful download removes the entry, so the count lives here, per process, and once a node
 * has been re-queued [maxRequeues] times its stored file is left alone and the viewer falls back
 * to the thumbnail. The map holds at most [maxTracked] nodes, least recently invalidated first
 * out, and a user's entries go with the user's session.
 */
internal class ProtonRenditionInvalidationLimiter(
    private val maxRequeues: Int = MAX_REQUEUES,
    private val maxTracked: Int = MAX_TRACKED,
) {
    private val requeues =
        object : LinkedHashMap<Key, Int>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Int>): Boolean = size > maxTracked
        }

    /** Records one more invalidation of the node; true while it may still be queued again. */
    @Synchronized
    fun allowsRequeue(
        userId: String,
        nodeUid: String,
    ): Boolean {
        val key = Key(userId, nodeUid)
        val count = (requeues[key] ?: 0) + 1
        requeues[key] = count
        return count <= maxRequeues
    }

    @Synchronized
    fun forget(userId: String) {
        requeues.keys.removeAll { key -> key.userId == userId }
    }

    private data class Key(
        val userId: String,
        val nodeUid: String,
    )

    companion object {
        const val MAX_REQUEUES = 3
        const val MAX_TRACKED = 1_000
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
