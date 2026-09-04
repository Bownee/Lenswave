package com.bownee.lenswave.viewer

import kotlin.math.max

/**
 * Sizes the down-sampled base bitmap the viewer shows at fit zoom. The base only has to fill the
 * view; anything sharper is what detail tiles are for, so its budget follows the view rather than
 * a fixed megapixel count.
 */
internal object PhotoBaseDecodePolicy {
    /** A little more than the view so the base still looks sharp at the fit scale's rounding. */
    private const val VIEW_BUDGET_FACTOR = 1.3

    /** Smallest budget, so a not-yet-laid-out or tiny view still decodes a usable base. */
    const val MIN_BASE_PIXELS = 1_500_000L

    /** Pixel budget of the base for a view of the given size, or the display's when the view has none yet. */
    fun budget(
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Long {
        val width = if (viewWidth > 0 && viewHeight > 0) viewWidth else displayWidth
        val height = if (viewWidth > 0 && viewHeight > 0) viewHeight else displayHeight
        return max(MIN_BASE_PIXELS, (VIEW_BUDGET_FACTOR * width.toLong() * height.toLong()).toLong())
    }

    /** Power-of-two sample that brings [width] x [height] under [budgetPixels]. */
    fun sampleSize(
        width: Int,
        height: Int,
        budgetPixels: Long,
    ): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > budgetPixels && sample < MAX_SAMPLE) sample *= 2
        return sample
    }

    /**
     * True when the container cannot carry transparency, so the base can be decoded to 16-bit
     * RGB 565 at half the memory of ARGB 8888 without losing anything the file had.
     */
    fun isOpaque(mimeType: String?): Boolean =
        when (mimeType?.lowercase()) {
            "image/jpeg", "image/heic", "image/heif" -> true
            else -> false
        }

    private const val MAX_SAMPLE = 1 shl 16
}
