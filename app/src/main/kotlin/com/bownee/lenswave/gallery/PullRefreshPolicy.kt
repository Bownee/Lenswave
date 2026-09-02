package com.bownee.lenswave.gallery

import kotlin.math.abs

object PullRefreshPolicy {
    fun isPullGesture(
        startedAtTop: Boolean,
        startedInFastScrollRegion: Boolean,
        horizontalDistance: Float,
        downwardDistance: Float,
    ): Boolean = startedAtTop &&
        !startedInFastScrollRegion &&
        downwardDistance > 0f &&
        downwardDistance > abs(horizontalDistance) * MIN_VERTICAL_RATIO

    fun progress(downwardDistance: Float, threshold: Float): Float {
        if (threshold <= 0f) return 0f
        return (downwardDistance / threshold).coerceIn(0f, 1f)
    }

    fun shouldRefresh(
        startedAtTop: Boolean,
        startedInFastScrollRegion: Boolean,
        horizontalDistance: Float,
        downwardDistance: Float,
        threshold: Float,
    ): Boolean = isPullGesture(
        startedAtTop,
        startedInFastScrollRegion,
        horizontalDistance,
        downwardDistance,
    ) && downwardDistance >= threshold

    private const val MIN_VERTICAL_RATIO = 1.25f
}
