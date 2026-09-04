package com.bownee.lenswave.metadata

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Turns raw EXIF numbers into the notation photographers expect. */
internal object ExifValueFormatter {
    /**
     * Exposure time in seconds as a shutter-speed fraction: `1/2890` for 0.000346, `1/2` for 0.5,
     * `2.5` for 2.5 and `30` for 30. ExifInterface's own string form is scientific notation.
     */
    fun exposureTime(seconds: Double): String? {
        if (seconds <= 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
        if (seconds < 1.0) {
            val denominator = (1.0 / seconds).roundToLong().coerceAtLeast(1L)
            // Anything that rounds back to a whole second is shown as one (1/1 reads wrong).
            return if (denominator == 1L) "1" else "1/$denominator"
        }
        val rounded = seconds.roundToLong()
        return if (abs(seconds - rounded) < 0.05) rounded.toString() else String.format(Locale.US, "%.1f", seconds)
    }
}
