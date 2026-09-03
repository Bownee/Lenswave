package com.bownee.lenswave.viewer

import kotlin.math.max
import kotlin.math.roundToInt

internal object VerticalGesturePolicy {
    fun shouldSettleSheet(
        distance: Float,
        velocity: Float,
        sheetHeight: Float,
        density: Float,
    ): Boolean = distance >= max(96f * density, sheetHeight * 0.22f) ||
        (distance >= 44f * density && velocity >= 900f * density)

    fun detailsSettleOffset(
        currentOffset: Int,
        velocity: Float,
        initialOffset: Int,
        maximumOffset: Int,
    ): Int {
        if (maximumOffset <= 0) return 0
        val boundedInitialOffset = initialOffset.coerceIn(0, maximumOffset)
        val projectedOffset = (currentOffset + velocity * FLING_PROJECTION_SECONDS)
            .roundToInt()
            .coerceIn(0, maximumOffset)
        return if (projectedOffset < boundedInitialOffset * HIDE_THRESHOLD_FRACTION) {
            0
        } else {
            max(boundedInitialOffset, projectedOffset)
        }
    }

    fun shouldDismissViewer(
        distance: Float,
        velocity: Float,
        viewerHeight: Float,
        density: Float,
    ): Boolean = distance >= max(80f * density, viewerHeight * 0.12f) ||
        (distance >= 40f * density && velocity >= 900f * density)

    private const val FLING_PROJECTION_SECONDS = 0.12f
    private const val HIDE_THRESHOLD_FRACTION = 0.45f
}
