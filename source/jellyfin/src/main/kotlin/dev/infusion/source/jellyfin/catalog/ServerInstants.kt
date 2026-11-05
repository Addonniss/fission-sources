package dev.infusion.source.jellyfin.catalog

import java.time.Instant
import java.time.format.DateTimeParseException

internal fun parseServerInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
}
