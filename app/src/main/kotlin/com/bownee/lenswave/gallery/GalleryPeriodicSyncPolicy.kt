package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonSyncSource
import java.time.Duration

/**
 * Paces the quiet metadata checks that run while the gallery is on screen. A check asks the
 * repository for a non-forced sync, which re-enumerates only once the cached listing is older
 * than its freshness limit, so checking at that limit plus a little slack (the sync itself takes
 * a few seconds) re-enumerates on every check without wasted ticks in between.
 */
internal object GalleryPeriodicSyncPolicy {
    val CHECK_INTERVAL_MILLIS: Long = ProtonSyncSource.TIMELINE.maximumAgeMillis + Duration.ofSeconds(30).toMillis()

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
