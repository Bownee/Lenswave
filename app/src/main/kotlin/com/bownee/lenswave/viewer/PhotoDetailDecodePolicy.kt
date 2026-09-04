package com.bownee.lenswave.viewer

/**
 * Decides whether the zoomed photo needs a sharper tile decoded on top of its down-sampled base
 * bitmap, at which sample size, and for which part of the picture. Coordinates are in oriented
 * image pixels; the view maps the result back to the decoder's raw axes.
 */
internal object PhotoDetailDecodePolicy {
    /** Pixel budget of one detail tile, the same as the base bitmap's. */
    const val MAX_DETAIL_PIXELS = 4_000_000L

    /** Extra picture decoded around the viewport so small pans do not immediately need a new tile. */
    private const val MARGIN_DIVISOR = 4

    data class Region(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Plan(
        val sampleSize: Int,
        val region: Region,
    )

    /**
     * The sample size of a detail tile, or null when the base bitmap already carries at least one
     * image pixel per screen pixel at [scale] (so a sharper tile would show nothing new).
     */
    fun sampleSize(
        scale: Float,
        baseSampleSize: Int,
    ): Int? {
        if (baseSampleSize <= 1 || scale * baseSampleSize <= 1f) return null
        var sample = 1
        while (sample * 2 < baseSampleSize && scale * sample * 2 <= 1f) sample *= 2
        return sample
    }

    /**
     * What to decode for [visible] (the viewport in image pixels): the viewport plus a margin,
     * clamped to the image and kept under [MAX_DETAIL_PIXELS]. When the tile would be too large the
     * margin goes first, then the sample size rises; null when no tile within budget beats the base.
     */
    fun plan(
        scale: Float,
        baseSampleSize: Int,
        visible: Region,
        imageWidth: Int,
        imageHeight: Int,
    ): Plan? {
        var sample = sampleSize(scale, baseSampleSize) ?: return null
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val viewport = clamp(visible, imageWidth, imageHeight) ?: return null
        val marginX = viewport.width / MARGIN_DIVISOR
        val marginY = viewport.height / MARGIN_DIVISOR
        val withMargin =
            clamp(
                Region(
                    viewport.left - marginX,
                    viewport.top - marginY,
                    viewport.right + marginX,
                    viewport.bottom + marginY,
                ),
                imageWidth,
                imageHeight,
            ) ?: viewport
        val region = if (decodedPixels(withMargin, sample) <= MAX_DETAIL_PIXELS) withMargin else viewport
        while (decodedPixels(region, sample) > MAX_DETAIL_PIXELS) {
            if (sample * 2 >= baseSampleSize) return null
            sample *= 2
        }
        return Plan(sample, region)
    }

    private fun decodedPixels(
        region: Region,
        sample: Int,
    ): Long = (region.width.toLong() / sample) * (region.height.toLong() / sample)

    private fun clamp(
        region: Region,
        imageWidth: Int,
        imageHeight: Int,
    ): Region? {
        val left = region.left.coerceIn(0, imageWidth)
        val top = region.top.coerceIn(0, imageHeight)
        val right = region.right.coerceIn(0, imageWidth)
        val bottom = region.bottom.coerceIn(0, imageHeight)
        return if (right > left && bottom > top) Region(left, top, right, bottom) else null
    }
}
