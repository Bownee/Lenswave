package com.bownee.lenswave.proton

import java.time.Duration

enum class ProtonSyncSource(
    val maximumAgeMillis: Long,
) {
    TIMELINE(Duration.ofMinutes(15).toMillis()),
    ALBUMS(Duration.ofMinutes(30).toMillis()),
    ALBUM_PHOTOS(Duration.ofMinutes(15).toMillis()),
}

internal object ProtonSyncPolicy {
    fun shouldEnumerate(
        source: ProtonSyncSource,
        lastSuccessfulSyncMillis: Long,
        nowMillis: Long,
        forceRemote: Boolean,
        hasCachedSnapshot: Boolean,
    ): Boolean {
        if (forceRemote || !hasCachedSnapshot || lastSuccessfulSyncMillis <= 0L) return true
        if (nowMillis < lastSuccessfulSyncMillis) return true
        return nowMillis - lastSuccessfulSyncMillis >= source.maximumAgeMillis
    }
}
