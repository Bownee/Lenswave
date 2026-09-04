package com.bownee.lenswave.viewer

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides whether the zoomed photo needs a sharper tile decoded on top of its down-sampled base
 * bitmap, at which sample size, and for which part of the picture. Coordinates are in oriented
 * image pixels; the view maps the result back to the decoder's raw axes.
 */
internal object PhotoDetailDecodePolicy {
    /** Smallest tile budget, so a tiny view still gets a useful tile. */
    const val MIN_DETAIL_PIXELS = 1_000_000L

    /**
     * How much larger than the viewport a tile may be. At the sample the display needs, the
     * viewport decodes to between one and four times the screen's pixels; two keeps the common
     * case whole and lets the rest shrink slightly towards the centre.
     */
    private const val VIEWPORT_BUDGET_FACTOR = 2L

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

    /** Pixel budget of one detail tile for a view of the given size. */
    fun budget(
        viewWidth: Int,
        viewHeight: Int,
    ): Long = max(MIN_DETAIL_PIXELS, VIEWPORT_BUDGET_FACTOR * viewWidth.toLong() * viewHeight.toLong())

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
     * clamped to the image and kept under [budgetPixels]. When the tile would be too large the
     * margin goes first, then the sample rises while it still beats the base, and finally the
     * region shrinks towards the viewport's centre so the middle of the screen is always sharp.
     */
    fun plan(
        scale: Float,
        baseSampleSize: Int,
        visible: Region,
        imageWidth: Int,
        imageHeight: Int,
        budgetPixels: Long,
    ): Plan? {
        var sample = sampleSize(scale, baseSampleSize) ?: return null
        if (imageWidth <= 0 || imageHeight <= 0 || budgetPixels <= 0) return null
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
        var region = if (decodedPixels(withMargin, sample) <= budgetPixels) withMargin else viewport
        while (decodedPixels(region, sample) > budgetPixels && sample * 2 < baseSampleSize) sample *= 2
        if (decodedPixels(region, sample) > budgetPixels) region = shrinkToCentre(region, sample, budgetPixels)
        return Plan(sample, region)
    }

    private fun shrinkToCentre(
        region: Region,
        sample: Int,
        budgetPixels: Long,
    ): Region {
        val factor = sqrt(budgetPixels.toDouble() / decodedPixels(region, sample))
        val width = max(sample, (region.width * factor).toInt())
        val height = max(sample, (region.height * factor).toInt())
        val left = region.left + (region.width - width) / 2
        val top = region.top + (region.height - height) / 2
        return Region(left, top, left + width, top + height)
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
