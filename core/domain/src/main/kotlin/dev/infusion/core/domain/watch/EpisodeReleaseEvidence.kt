package dev.infusion.core.domain.watch

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

enum class EpisodeAirStatus {

    AIRED,

    NOT_YET_AIRED,

    UNKNOWN,
}

object EpisodeReleaseEvidence {

    fun parse(raw: String?): Pair<LocalDate?, Instant?> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        runCatching { return LocalDate.parse(text) to null }
        runCatching { Instant.parse(text) }.getOrNull()?.let { instant ->
            return instant.atZone(ZoneId.systemDefault()).toLocalDate() to instant
        }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let { offset ->
            return offset.toLocalDate() to offset.toInstant()
        }
        return null to null
    }

    fun airStatus(
        airedAt: LocalDate?,
        airedAtProvenAt: Instant?,
        today: LocalDate,
        now: Instant?,
    ): EpisodeAirStatus = when {
        airedAtProvenAt != null && now != null ->
            if (airedAtProvenAt.isAfter(now)) EpisodeAirStatus.NOT_YET_AIRED else EpisodeAirStatus.AIRED
        airedAt != null ->
            if (airedAt.isAfter(today)) EpisodeAirStatus.NOT_YET_AIRED else EpisodeAirStatus.AIRED
        else -> EpisodeAirStatus.UNKNOWN
    }

    fun airStatusOf(
        released: String?,
        today: LocalDate,
        now: Instant? = null,
    ): EpisodeAirStatus {
        val (date, instant) = parse(released)
        return airStatus(date, instant, today, now)
    }
}
