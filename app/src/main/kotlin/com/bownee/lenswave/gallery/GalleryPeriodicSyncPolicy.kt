package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonSyncSource

/**
 * Paces the quiet metadata checks that run while the gallery is on screen. A check asks the
 * repository for a non-forced sync, which re-enumerates only once the cached listing is older
 * than its freshness limit.
 *
 * The interval is a multiple of that limit, not the limit itself. Checking at the limit made
 * every tick a full enumeration of the library, an expensive round trip for a screen that is
 * mostly looking at photos it already has; the user's own actions (a resume, a tab switch, an
 * album, a manual refresh) refresh the listing far more often than a timer needs to. The timer
 * is only the backstop for a gallery left on screen, and a tick that lands within the freshness
 * limit of one of those actions is a cheap no-op. The immediate check when the last one is
 * overdue, for a gallery coming back after a long time in the background, is unchanged.
 */
internal object GalleryPeriodicSyncPolicy {
    const val CHECK_INTERVAL_MULTIPLE = 3L
    val CHECK_INTERVAL_MILLIS: Long = ProtonSyncSource.TIMELINE.maximumAgeMillis * CHECK_INTERVAL_MULTIPLE

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
}
