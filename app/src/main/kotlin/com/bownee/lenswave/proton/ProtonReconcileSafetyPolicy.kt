package com.bownee.lenswave.proton

/**
 * Whether a remote listing may replace the cached one.
 *
 * Reconciling is destructive: every photo the timeline listing lacks loses its thumbnail, its
 * preview, its original and its tag entries; an album, album-photo or tag listing that lost
 * entries empties the corresponding screen. A listing that drops most of a large library at once
 * is far more likely a truncated enumeration (a flaky connection, a server that answered half the
 * pages) than a real mass deletion, so an automatic refresh refuses it and keeps the cached
 * listing. A refresh the user asked for is trusted: a real mass deletion is then one pull away,
 * and the failed automatic refresh is what offers it.
 */
internal object ProtonReconcileSafetyPolicy {
    /** A listing that drops more than this share of the cached photos is suspicious... */
    const val SUSPICIOUS_REMOVAL_SHARE_PERCENT = 50

    /** ...but only from this many removals on; a small library can honestly lose most of itself. */
    const val MINIMUM_SUSPICIOUS_REMOVALS = 200

    fun mayCommit(
        cachedCount: Int,
        removedCount: Int,
        forceRemote: Boolean,
    ): Boolean {
        if (forceRemote || cachedCount <= 0) return true
        if (removedCount < MINIMUM_SUSPICIOUS_REMOVALS) return true
        return removedCount * 100L <= cachedCount.toLong() * SUSPICIOUS_REMOVAL_SHARE_PERCENT
    }

    /**
     * [mayCommit] for a listing of [existing] entries the remote enumeration answered with
     * [remoteNodeUids]; throws [ProtonSuspiciousListingException] when it refuses. Thrown from a
     * sync commit, the refusal is reported and published as a failed refresh like any other,
     * which is what offers the manual refresh.
     */
    fun <T> requireCommit(
        listing: String,
        existing: List<T>,
        remoteNodeUids: Set<String>,
        forceRemote: Boolean,
        nodeUid: (T) -> String,
    ) {
        if (forceRemote || existing.isEmpty()) return
        val removedCount = existing.count { entry -> nodeUid(entry) !in remoteNodeUids }
        if (!mayCommit(existing.size, removedCount, forceRemote)) {
            throw ProtonSuspiciousListingException(listing, existing.size, removedCount)
        }
    }

    /**
     * [requireCommit] for a timeline whose cached listing could not be read: absent on a fresh
     * account, or unreadable for a moment (a Keystore hiccup) or discarded as corrupt. The stored
     * thumbnails stand in for the listing then; a fresh account has none and passes, while a
     * remote listing that lacks most of the photos whose thumbnails are stored is refused just
     * as it would be against the listing itself.
     */
    fun requireCommitOverStoredThumbnails(
        listing: String,
        storedThumbnailCount: Int,
        remoteStoredThumbnailCount: Int,
        forceRemote: Boolean,
    ) {
        val removedCount = storedThumbnailCount - remoteStoredThumbnailCount
        if (!mayCommit(storedThumbnailCount, removedCount, forceRemote)) {
            throw ProtonSuspiciousListingException(listing, storedThumbnailCount, removedCount)
        }
    }
}

/** An automatic refresh refused to reconcile a listing that [ProtonReconcileSafetyPolicy] judged suspicious. */
internal class ProtonSuspiciousListingException(
    listing: String,
    cachedCount: Int,
    removedCount: Int,
) : IllegalStateException(
        "Remote $listing listing dropped $removedCount of $cachedCount cached entries; refusing to reconcile without a manual refresh",
    )
