package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonSyncSource

/**
 * Paces the quiet metadata checks that run while the gallery is on screen.
 *
 * A check that goes through to the repository is never free: even a non-forced sync whose
 * listing is still fresh reconciles both thumbnail queues against the library and asks for the
 * tag listings, and one whose listing is older than the freshness limit enumerates the whole
 * timeline. The interval is a multiple of that limit, so a tick always finds the listing stale
 * and would always enumerate; the user's own actions (a resume, a tab switch, an album, a manual
 * refresh) refresh the listing far more often than a timer needs to, and the timer is only the
 * backstop for a gallery left on screen.
 *
 * So a tick asks for a refresh only when [shouldRefresh] says the last completed refresh is at
 * least a freshness limit old, which is exactly when the repository would enumerate. A tick that
 * lands within the freshness limit of a user-driven refresh does nothing at all. The immediate
 * check when the last one is overdue, for a gallery coming back after a long time in the
 * background, is unchanged.
 */
internal object GalleryPeriodicSyncPolicy {
    const val CHECK_INTERVAL_MULTIPLE = 3L
    val FRESHNESS_LIMIT_MILLIS: Long = ProtonSyncSource.TIMELINE.maximumAgeMillis
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
     * old, so the repository would enumerate rather than only walk the library.
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
