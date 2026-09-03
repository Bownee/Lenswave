package com.bownee.lenswave

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PhotoViewerTitleFormatter {
    fun format(
        capturedAtEpochMillis: Long,
        zoneId: ZoneId,
        locale: Locale,
        use24HourTime: Boolean,
    ): String? {
        if (capturedAtEpochMillis <= 0L) return null
        val pattern = if (use24HourTime) {
            "EEE, d MMM uuuu · HH:mm"
        } else {
            "EEE, d MMM uuuu · h:mm a"
        }
        return Instant.ofEpochMilli(capturedAtEpochMillis)
            .atZone(zoneId)
            .format(DateTimeFormatter.ofPattern(pattern, locale))
            .replaceFirstChar { character -> character.titlecase(locale) }
    }
}
