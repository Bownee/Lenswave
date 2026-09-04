package com.bownee.lenswave.viewer

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object PhotoViewerTitleFormatter {
    /** Compiled once per locale and clock style; building a DateTimeFormatter per swipe is not free. */
    private val formatters = ConcurrentHashMap<Pair<Locale, Boolean>, DateTimeFormatter>()

    fun format(
        capturedAtEpochMillis: Long,
        zoneId: ZoneId,
        locale: Locale,
        use24HourTime: Boolean,
    ): String? {
        if (capturedAtEpochMillis <= 0L) return null
        return Instant
            .ofEpochMilli(capturedAtEpochMillis)
            .atZone(zoneId)
            .format(formatter(locale, use24HourTime))
            .replaceFirstChar { character -> character.titlecase(locale) }
    }

    private fun formatter(
        locale: Locale,
        use24HourTime: Boolean,
    ): DateTimeFormatter =
        formatters.getOrPut(locale to use24HourTime) {
            val pattern =
                if (use24HourTime) {
                    "EEE, d MMM uuuu · HH:mm"
                } else {
                    "EEE, d MMM uuuu · h:mm a"
                }
            DateTimeFormatter.ofPattern(pattern, locale)
        }
}
