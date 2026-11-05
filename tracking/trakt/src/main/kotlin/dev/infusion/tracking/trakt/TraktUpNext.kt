package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.upnext.UpNextTrackerKind
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import dev.infusion.core.domain.upnext.UpNextTrackerRecord
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

object TraktUpNext {

    const val UP_NEXT_PATH: String = "/sync/progress/up_next"

    const val PAGE_COUNT_HEADER: String = "X-Pagination-Page-Count"

    fun request(page: Int, limit: Int): TraktRequest = TraktRequest(
        method = TraktMethod.GET,
        path = UP_NEXT_PATH,
        query = mapOf(
            "limit" to limit.coerceAtLeast(1).toString(),
            "page" to page.coerceAtLeast(1).toString(),

            "specials" to "false",
        ),
        authenticated = true,
        retryable = true,
    )

    fun pageCount(headers: Map<String, String>): Int? =
        headers.entries.firstOrNull { it.key.equals(PAGE_COUNT_HEADER, ignoreCase = true) }
            ?.value?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    fun page(body: String, limit: Int, page: Int, pageCount: Int?): UpNextTrackerPage? = runCatching {
        val array = JSONArray(body)
        val records = ArrayList<UpNextTrackerRecord>(array.length())
        var malformed = 0
        var skipped = 0
        for (index in 0 until array.length()) {
            when (val parsed = record(array.optJSONObject(index))) {
                is Parsed.Record -> records += parsed.record
                Parsed.Malformed -> malformed++
                Parsed.NoStartEvidence -> skipped++
            }
        }
        UpNextTrackerPage(
            records = records,
            malformed = malformed,
            skipped = skipped,

            truncated = pageCount?.let { it > page } ?: (array.length() >= limit),
        )
    }.getOrNull()

    private sealed interface Parsed {
        data class Record(val record: UpNextTrackerRecord) : Parsed
        data object Malformed : Parsed
        data object NoStartEvidence : Parsed
    }

    private fun record(item: JSONObject?): Parsed {
        if (item == null) return Parsed.Malformed
        val show = item.optJSONObject("show") ?: return Parsed.Malformed
        val ids = idsOf(show) ?: return Parsed.Malformed
        val progress = item.optJSONObject("progress") ?: return Parsed.Malformed
        val next = progress.optJSONObject("next_episode") ?: return Parsed.Malformed
        val season = intOrNull(next.opt("season"))
        val number = intOrNull(next.opt("number"))
        if (season == null || season <= 0 || number == null || number <= 0) return Parsed.Malformed

        val completed = intOrNull(progress.opt("completed")) ?: 0
        val lastWatchedAt = instant(progress.opt("last_watched_at"))
        val resetAt = instant(progress.opt("reset_at"))
        if (completed <= 0 && lastWatchedAt == null && resetAt == null) return Parsed.NoStartEvidence

        val provenAt = instant(next.opt("effective_release_date")) ?: instant(next.opt("first_aired"))
        val releasedDate = localDate(next.opt("released"))
            ?: provenAt?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
        return Parsed.Record(
            UpNextTrackerRecord(
                kind = UpNextTrackerKind.EPISODE,
                externalIds = ids,
                seasonNumber = season,
                episodeNumber = number,
                episodeTitle = next.optString("title", "").trim().ifEmpty { null },
                seriesTitle = show.optString("title", "").trim().ifEmpty { null },
                year = intOrNull(show.opt("year")),

                activityAt = lastWatchedAt ?: resetAt,
                airedAt = releasedDate,
                airedAtProvenAt = provenAt,
                providerRecordId = next.opt("ids")?.let { nextIds ->
                    (nextIds as? JSONObject)?.opt("trakt")?.let { scalar(it) }
                },
            ),
        )
    }

    private fun idsOf(container: JSONObject): TrackingExternalIds? {
        val ids = container.optJSONObject("ids") ?: return null
        val parsed = TrackingExternalIds(
            tmdbId = positiveLong(ids.opt("tmdb")),
            imdbId = ids.opt("imdb")?.let { scalar(it) }?.takeIf { it.isNotBlank() },
            tvdbId = positiveLong(ids.opt("tvdb")),
        )
        return parsed.takeIf { !it.isEmpty }
    }

    private fun instant(value: Any?): Instant? {
        val text = scalar(value)?.takeIf { it.isNotBlank() } ?: return null
        runCatching { return Instant.parse(text) }
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    private fun localDate(value: Any?): java.time.LocalDate? {
        val text = scalar(value)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.time.LocalDate.parse(text) }.getOrNull()
    }

    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim()
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    private fun intOrNull(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun positiveLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }
}
