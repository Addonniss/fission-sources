package dev.infusion.tracking.mdblist

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.upnext.UpNextTrackerKind
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import dev.infusion.core.domain.upnext.UpNextTrackerRecord
import java.time.Instant
import org.json.JSONObject

object MDBListUpNext {

    const val UP_NEXT_PATH: String = "/upnext"

    fun request(page: Int, limit: Int): MDBListRequest {
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val safePage = page.coerceAtLeast(1)
        return MDBListRequest(
            method = MDBListMethod.GET,
            path = UP_NEXT_PATH,
            query = mapOf(
                "limit" to safeLimit.toString(),
                "offset" to ((safePage - 1) * safeLimit).toString(),

            ),
            authenticated = true,
            retryable = true,
            readBudget = MDBListReadBudget.LOOKUP,
        )
    }

    const val MAX_LIMIT: Int = 100

    fun page(body: String, limit: Int): UpNextTrackerPage? = runCatching {
        val root = JSONObject(body)
        val items = root.optJSONArray("items") ?: return@runCatching null
        val records = ArrayList<UpNextTrackerRecord>(items.length())
        var malformed = 0
        var skipped = 0
        for (index in 0 until items.length()) {
            when (val parsed = record(items.optJSONObject(index))) {
                is Parsed.Record -> records += parsed.record
                Parsed.Malformed -> malformed++
                Parsed.NoNextEpisode -> skipped++
                Parsed.NotStarted -> skipped++
            }
        }
        UpNextTrackerPage(
            records = records,
            malformed = malformed,
            skipped = skipped,

            truncated = boolean(root.opt("has_more")) ?: (items.length() >= limit),
        )
    }.getOrNull()

    private sealed interface Parsed {
        data class Record(val record: UpNextTrackerRecord) : Parsed
        data object Malformed : Parsed
        data object NoNextEpisode : Parsed
        data object NotStarted : Parsed
    }

    private fun record(item: JSONObject?): Parsed {
        if (item == null) return Parsed.Malformed
        val show = item.optJSONObject("show") ?: item
        val ids = idsOf(show) ?: idsOf(item) ?: return Parsed.Malformed
        val next = item.optJSONObject("next_episode") ?: return Parsed.NoNextEpisode
        val season = intOrNull(next.opt("season"))
            ?: intOrNull(next.opt("season_number"))
            ?: return Parsed.Malformed
        val number = intOrNull(next.opt("episode"))
            ?: intOrNull(next.opt("number"))
            ?: intOrNull(next.opt("episode_number"))
            ?: return Parsed.Malformed
        if (season <= 0 || number <= 0) return Parsed.Malformed

        val progress = item.optJSONObject("progress")
        val lastWatchedAt = instant(item.opt("last_watched_at"))
            ?: instant(progress?.opt("last_watched_at"))
        val completed = intOrNull(progress?.opt("completed")) ?: 0
        if (lastWatchedAt == null && completed <= 0) return Parsed.NotStarted

        val airDate = localDate(next.opt("air_date")) ?: localDate(item.opt("air_date"))
        return Parsed.Record(
            UpNextTrackerRecord(
                kind = UpNextTrackerKind.EPISODE,
                externalIds = ids,
                seasonNumber = season,
                episodeNumber = number,
                episodeTitle = next.optString("title", "").trim().ifEmpty { null },
                seriesTitle = show.optString("title", "").trim().ifEmpty { null },
                year = intOrNull(show.opt("year")),
                activityAt = lastWatchedAt,
                airedAt = airDate,
                providerRecordId = null,
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

    private fun boolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.trim().lowercase().let { if (it == "true") true else if (it == "false") false else null }
        else -> null
    }

    private fun instant(value: Any?): Instant? {
        val text = scalar(value)?.takeIf { it.isNotBlank() } ?: return null

        text.toLongOrNull()?.let { seconds -> return runCatching { Instant.ofEpochSecond(seconds) }.getOrNull() }
        return runCatching { Instant.parse(text) }.getOrNull()
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
