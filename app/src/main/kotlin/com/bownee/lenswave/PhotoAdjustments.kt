package com.bownee.lenswave

internal class PhotoAdjustments(
    val brightness: Float,
    val contrast: Float,
    val highlights: Float,
    val shadows: Float,
    val saturation: Float,
    val warmth: Float,
    val tint: Float,
    val vignette: Float,
    rotationQuarterTurns: Int,
) {
    val rotationQuarterTurns: Int = Math.floorMod(rotationQuarterTurns, 4)

    fun value(adjustment: Int): Float = when (adjustment) {
        BRIGHTNESS -> brightness
        CONTRAST -> contrast
        HIGHLIGHTS -> highlights
        SHADOWS -> shadows
        SATURATION -> saturation
        WARMTH -> warmth
        TINT -> tint
        VIGNETTE -> vignette
        else -> throw IllegalArgumentException("Unknown adjustment: $adjustment")
    }

    fun withValue(adjustment: Int, value: Float): PhotoAdjustments = when (adjustment) {
        BRIGHTNESS -> copy(brightness = value)
        CONTRAST -> copy(contrast = value)
        HIGHLIGHTS -> copy(highlights = value)
        SHADOWS -> copy(shadows = value)
        SATURATION -> copy(saturation = value)
        WARMTH -> copy(warmth = value)
        TINT -> copy(tint = value)
        VIGNETTE -> copy(vignette = value)
        else -> throw IllegalArgumentException("Unknown adjustment: $adjustment")
    }

    fun rotateClockwise(): PhotoAdjustments = copy(rotationQuarterTurns = rotationQuarterTurns + 1)

    private fun copy(
        brightness: Float = this.brightness,
        contrast: Float = this.contrast,
        highlights: Float = this.highlights,
        shadows: Float = this.shadows,
        saturation: Float = this.saturation,
        warmth: Float = this.warmth,
        tint: Float = this.tint,
        vignette: Float = this.vignette,
        rotationQuarterTurns: Int = this.rotationQuarterTurns,
    ): PhotoAdjustments = PhotoAdjustments(
        brightness,
        contrast,
        highlights,
        shadows,
        saturation,
        warmth,
        tint,
        vignette,
        rotationQuarterTurns,
    )

    override fun equals(other: Any?): Boolean = other is PhotoAdjustments &&
        brightness.compareTo(other.brightness) == 0 &&
        contrast.compareTo(other.contrast) == 0 &&
        highlights.compareTo(other.highlights) == 0 &&
        shadows.compareTo(other.shadows) == 0 &&
        saturation.compareTo(other.saturation) == 0 &&
        warmth.compareTo(other.warmth) == 0 &&
        tint.compareTo(other.tint) == 0 &&
        vignette.compareTo(other.vignette) == 0 &&
        rotationQuarterTurns == other.rotationQuarterTurns

    override fun hashCode(): Int {
        var result = brightness.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + highlights.hashCode()
        result = 31 * result + shadows.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + warmth.hashCode()
        result = 31 * result + tint.hashCode()
        result = 31 * result + vignette.hashCode()
        return 31 * result + rotationQuarterTurns
    }

    companion object {
        const val BRIGHTNESS = 0
        const val CONTRAST = 1
        const val HIGHLIGHTS = 2
        const val SHADOWS = 3
        const val SATURATION = 4
        const val WARMTH = 5
        const val TINT = 6
        const val VIGNETTE = 7

        val NEUTRAL = PhotoAdjustments(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)
    }
}
