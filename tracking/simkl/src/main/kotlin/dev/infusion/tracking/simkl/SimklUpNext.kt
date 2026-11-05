package dev.infusion.tracking.simkl

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.upnext.UpNextTrackerKind
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import dev.infusion.core.domain.upnext.UpNextTrackerRecord
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

object SimklUpNext {

    const val WATCHING_PATH: String = "/sync/all-items/shows/watching"

    const val MAX_RECORDS: Int = 200

    private val EPISODE_CODE = Regex("^[Ss](\\d{1,3})[Ee](\\d{1,4})$")

    fun request(): SimklRequest = SimklRequest(
        method = SimklMethod.GET,
        path = WATCHING_PATH,

        query = emptyMap(),
        authenticated = true,
        retryable = true,
        readBudget = SimklReadBudget.UP_NEXT,
    )

    fun page(body: String, limit: Int): UpNextTrackerPage? = runCatching {
        val elements = elements(body) ?: return@runCatching null
        val records = ArrayList<UpNextTrackerRecord>()
        var malformed = 0
        var skipped = 0
        for (element in elements) {
            when (val parsed = record(element)) {
                is Parsed.Record -> if (records.size < MAX_RECORDS) records += parsed.record else skipped++
                Parsed.Malformed -> malformed++
                Parsed.NoNextEpisode -> skipped++
            }
        }
        UpNextTrackerPage(
            records = records,
            malformed = malformed,
            skipped = skipped,

            truncated = false,
        )
    }.getOrNull()

    private fun elements(body: String): List<JSONObject?>? {
        val text = body.trim()
        if (text.startsWith("[")) {
            val array = JSONArray(text)
            return (0 until array.length()).map { array.optJSONObject(it) }
        }
        if (!text.startsWith("{")) return null
        val root = JSONObject(text)
        val array = root.optJSONArray("shows")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("watching")
            ?: return null
        return (0 until array.length()).map { array.optJSONObject(it) }
    }

    private sealed interface Parsed {
        data class Record(val record: UpNextTrackerRecord) : Parsed
        data object Malformed : Parsed
        data object NoNextEpisode : Parsed
    }

    private fun record(item: JSONObject?): Parsed {
        if (item == null) return Parsed.Malformed
        val show = item.optJSONObject("show") ?: item
        val ids = idsOf(show) ?: return Parsed.Malformed
        val next = item.opt("next_to_watch")
        when (next) {
            null, JSONObject.NULL -> return Parsed.NoNextEpisode
        }
        val coordinates = coordinates(next) ?: return Parsed.Malformed
        val lastWatched = instant(item.opt("last_watched_at"))
            ?: instant(item.opt("last_watched"))
            ?: instant((next as? JSONObject)?.optJSONObject("episode")?.opt("watched_at"))
            ?: instant((next as? JSONObject)?.opt("watched_at"))

        return Parsed.Record(
            UpNextTrackerRecord(
                kind = UpNextTrackerKind.EPISODE,
                externalIds = ids,
                seasonNumber = coordinates.seasonNumber,
                episodeNumber = coordinates.episodeNumber,
                episodeTitle = coordinates.title,
                seriesTitle = show.optString("title", "").trim().ifEmpty { null },
                year = intOrNull(show.opt("year")),
                activityAt = lastWatched,
                providerRecordId = coordinates.providerRecordId,
            ),
        )
    }

    private fun coordinates(next: Any?): Coordinates? = when (next) {
        is JSONObject -> {
            val nested = next.optJSONObject("episode")
            val season = intOrNull(next.opt("season")) ?: intOrNull(nested?.opt("season"))
            val number = intOrNull(next.opt("episode"))?.takeIf { it > 0 }
                ?: intOrNull(nested?.opt("episode"))
                ?: intOrNull(next.opt("number"))
            if (season == null || season <= 0 || number == null || number <= 0) {
                null
            } else {
                Coordinates(
                    seasonNumber = season,
                    episodeNumber = number,
                    title = nested?.optString("title", "")?.trim()?.ifEmpty { null }
                        ?: next.optString("title", "").trim().ifEmpty { null },
                    providerRecordId = next.opt("ids")?.let { raw ->
                        (raw as? JSONObject)?.opt("simkl")?.let(::scalar)
                    },
                )
            }
        }
        is String -> episodeCode(next)
        else -> null
    }

    private fun episodeCode(value: String): Coordinates? {
        val match = EPISODE_CODE.matchEntire(value.trim()) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        if (season <= 0 || episode <= 0) return null
        return Coordinates(seasonNumber = season, episodeNumber = episode, title = null, providerRecordId = null)
    }

    private data class Coordinates(
        val seasonNumber: Int,
        val episodeNumber: Int,
        val title: String?,
        val providerRecordId: String?,
    )

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
        return runCatching { Instant.parse(text) }.getOrNull()
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
