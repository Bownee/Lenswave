package com.bownee.lenswave.proton

/**
 * Whether a remote timeline listing may replace the cached one.
 *
 * Reconciling is destructive: every photo the listing lacks loses its thumbnail, its preview,
 * its original and its tag entries. A listing that drops most of a large library at once is far
 * more likely a truncated enumeration (a flaky connection, a server that answered half the
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
}

/** An automatic refresh refused to reconcile a listing that [ProtonReconcileSafetyPolicy] judged suspicious. */
internal class ProtonSuspiciousListingException(
    cachedCount: Int,
    removedCount: Int,
) : IllegalStateException(
        "Remote timeline dropped $removedCount of $cachedCount cached photos; refusing to reconcile without a manual refresh",
    )
