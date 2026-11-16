package dev.infusion.source.jellyfin.catalog

import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal fun parseServerInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw).truncatedTo(ChronoUnit.MILLIS)
    } catch (_: DateTimeParseException) {
        null
    }
}
