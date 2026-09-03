package com.bownee.lenswave

import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The shared easing and timing for everything in the viewer that settles vertically: the details
 * sheet, the dismiss drag and the media views that follow it. Kept in one place so the three
 * always agree.
 */
internal object ViewerVerticalSettle {
    // Lazy so that [duration] stays usable from plain JVM unit tests without touching Android.
    val interpolator: PathInterpolator by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }

    /**
     * How long a settle animation covering [remainingDistance] pixels should take when the finger
     * let go at [velocity] pixels per second; [density] converts the velocity floor from dp.
     */
    fun duration(remainingDistance: Float, velocity: Float, density: Float): Long {
        val absoluteDistance = abs(remainingDistance)
        val absoluteVelocity = abs(velocity)
        if (absoluteDistance < 1f) return 0L
        val minimumVelocity = (MINIMUM_VELOCITY_DP * density + 0.5f).toInt()
        if (absoluteVelocity < minimumVelocity) return DEFAULT_DURATION_MILLIS
        return (absoluteDistance / absoluteVelocity * 800f)
            .roundToLong()
            .coerceIn(MINIMUM_DURATION_MILLIS, DEFAULT_DURATION_MILLIS)
    }

    private const val MINIMUM_VELOCITY_DP = 200
    private const val DEFAULT_DURATION_MILLIS = 260L
    private const val MINIMUM_DURATION_MILLIS = 140L
}
