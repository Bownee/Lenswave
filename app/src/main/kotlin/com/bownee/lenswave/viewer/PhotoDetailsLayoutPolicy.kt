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

    /**
     * True when an open sheet cannot be settled yet because the scrolling surface or its viewport
     * has no measured size: settling now would put the sheet at offset 0 and alpha 0, open but
     * invisible. A closed sheet needs no layout to stay closed.
     */
    fun awaitsLayout(
        shown: Boolean,
        surfaceHeight: Int,
        viewportHeight: Int,
    ): Boolean = shown && (surfaceHeight <= 0 || viewportHeight <= 0)
}
