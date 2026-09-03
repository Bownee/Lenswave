package com.bownee.lenswave.viewer

import kotlin.math.ceil

internal object PhotoDetailsLayoutPolicy {
    fun attachmentOffset(
        mediaHeight: Int,
        fittedImageBottom: Float?,
        overlap: Int,
    ): Int =
        fittedImageBottom
            ?.takeIf(Float::isFinite)
            ?.let { ceil(mediaHeight - it).toInt().coerceAtLeast(0) + overlap.coerceAtLeast(0) }
            ?: 0

    fun initialOffset(
        mediaHeight: Int,
        fittedImageBottom: Float?,
        overlap: Int,
        fallbackOffset: Int,
        maximumOffset: Int,
    ): Int {
        val sheetOffset =
            if (fittedImageBottom?.isFinite() == true) {
                fallbackOffset - attachmentOffset(mediaHeight, fittedImageBottom, overlap)
            } else {
                fallbackOffset
            }
        return sheetOffset.coerceIn(0, maximumOffset.coerceAtLeast(0))
    }

    fun maximumOffset(
        surfaceHeight: Int,
        viewportHeight: Int,
        attachmentOffset: Int,
    ): Int = (surfaceHeight - viewportHeight - attachmentOffset).coerceAtLeast(0)
}
