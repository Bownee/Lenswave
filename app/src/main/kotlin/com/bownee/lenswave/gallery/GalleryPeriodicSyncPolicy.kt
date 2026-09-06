package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonEventSync

/** Checks Drive events while visible; unchanged snapshots never expire with time. */
internal object GalleryPeriodicSyncPolicy {
    const val CHECK_INTERVAL_MULTIPLE = 2L
    val FRESHNESS_LIMIT_MILLIS: Long = ProtonEventSync.POLL_INTERVAL_MILLIS
    val CHECK_INTERVAL_MILLIS: Long = FRESHNESS_LIMIT_MILLIS * CHECK_INTERVAL_MULTIPLE

    /** How long to wait before the next check; zero when one is already due or none has run. */
    fun delayUntilNextCheckMillis(
        lastCheckMillis: Long?,
        nowMillis: Long,
        intervalMillis: Long = CHECK_INTERVAL_MILLIS,
    ): Long {
        require(intervalMillis > 0L) { "The check interval must be positive" }
        if (lastCheckMillis == null || nowMillis < lastCheckMillis) return 0L
        return (lastCheckMillis + intervalMillis - nowMillis).coerceAtLeast(0L)
    }

    /**
     * Whether a check should ask the repository for a refresh: yes when no refresh has completed
     * yet, when the clock went backwards, or when the last one is at least [freshnessLimitMillis]
     * old, so the shared event check is due. Only a remote change invalidates a listing.
     */
    fun shouldRefresh(
        lastRefreshMillis: Long?,
        nowMillis: Long,
        freshnessLimitMillis: Long = FRESHNESS_LIMIT_MILLIS,
    ): Boolean {
        require(freshnessLimitMillis > 0L) { "The freshness limit must be positive" }
        if (lastRefreshMillis == null || nowMillis < lastRefreshMillis) return true
        return nowMillis - lastRefreshMillis >= freshnessLimitMillis
    }
}
