package com.bownee.lenswave

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object PixelMath {
    fun adjustPixel(
        pixel: Int,
        normalizedX: Float,
        normalizedY: Float,
        adjustments: PhotoAdjustments,
    ): Int {
        val alpha = pixel ushr 24 and 0xff
        var red = (pixel ushr 16 and 0xff) / 255f
        var green = (pixel ushr 8 and 0xff) / 255f
        var blue = (pixel and 0xff) / 255f

        var luminance = luminance(red, green, blue)
        val shadowMask = 1f - smoothStep(0f, PhotoAdjustmentSpec.SHADOW_EDGE, luminance)
        val highlightMask = smoothStep(PhotoAdjustmentSpec.HIGHLIGHT_EDGE, 1f, luminance)
        val lightShift = adjustments.brightness +
            adjustments.shadows * shadowMask * PhotoAdjustmentSpec.LIGHT_STRENGTH +
            adjustments.highlights * highlightMask * PhotoAdjustmentSpec.LIGHT_STRENGTH

        red += lightShift
        green += lightShift
        blue += lightShift

        val contrastFactor = 1f + adjustments.contrast
        red = (red - 0.5f) * contrastFactor + 0.5f
        green = (green - 0.5f) * contrastFactor + 0.5f
        blue = (blue - 0.5f) * contrastFactor + 0.5f

        luminance = luminance(red, green, blue)
        val saturationFactor = 1f + adjustments.saturation
        red = mix(luminance, red, saturationFactor)
        green = mix(luminance, green, saturationFactor)
        blue = mix(luminance, blue, saturationFactor)

        red += adjustments.warmth * PhotoAdjustmentSpec.WARMTH_STRENGTH +
            adjustments.tint * PhotoAdjustmentSpec.TINT_RED_STRENGTH
        green -= adjustments.tint * PhotoAdjustmentSpec.TINT_GREEN_STRENGTH
        blue -= adjustments.warmth * PhotoAdjustmentSpec.WARMTH_STRENGTH +
            adjustments.tint * PhotoAdjustmentSpec.TINT_BLUE_STRENGTH

        val dx = normalizedX - 0.5f
        val dy = normalizedY - 0.5f
        val distance = sqrt(dx * dx + dy * dy) / PhotoAdjustmentSpec.CORNER_DISTANCE
        val vignetteMask = smoothStep(PhotoAdjustmentSpec.VIGNETTE_START, 1f, distance)
        val vignetteFactor = 1f - adjustments.vignette * vignetteMask * PhotoAdjustmentSpec.VIGNETTE_STRENGTH
        red *= vignetteFactor
        green *= vignetteFactor
        blue *= vignetteFactor

        return alpha shl 24 or (toByte(red) shl 16) or (toByte(green) shl 8) or toByte(blue)
    }

    private fun luminance(red: Float, green: Float, blue: Float): Float =
        red * PhotoAdjustmentSpec.LUMA_RED +
            green * PhotoAdjustmentSpec.LUMA_GREEN +
            blue * PhotoAdjustmentSpec.LUMA_BLUE

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val amount = clamp((value - edge0) / (edge1 - edge0))
        return amount * amount * (3f - 2f * amount)
    }

    private fun mix(from: Float, to: Float, amount: Float): Float = from * (1f - amount) + to * amount

    private fun toByte(value: Float): Int = (clamp(value) * 255f).roundToInt()

    private fun clamp(value: Float): Float = value.coerceIn(0f, 1f)
}
