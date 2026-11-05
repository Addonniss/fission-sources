package dev.infusion.tracking.simkl

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingPlaybackRequest
import dev.infusion.core.domain.tracking.TrackingSavedPlayback
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedQuery
import dev.infusion.core.domain.tracking.TrackingWatchedVerdict
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

object SimklInboundMapping {

    const val ACTIVITIES_PATH: String = "/sync/activities"

    const val PLAYBACK_PATH: String = "/sync/playback"

    private val PLAYBACK_DOMAINS = listOf("movies", "tv_shows", "anime")

    const val WATCHED_LOOKUP_PATH: String = "/sync/watched"

    private const val EXTENDED_EPISODES: String = "episodes"

    private val WATCHED_BUCKETS_BY_DOMAIN = mapOf(
        "tv_shows" to listOf("plantowatch", "watching", "completed", "hold", "dropped"),
        "movies" to listOf("plantowatch", "completed", "dropped"),
    )

    private val DATE_FROM_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun activitiesRequest(): SimklRequest = SimklRequest(
        method = SimklMethod.GET,
        path = ACTIVITIES_PATH,
        authenticated = true,

        retryable = true,
    )

    fun playbackRequest(request: TrackingPlaybackRequest): SimklRequest = SimklRequest(
        method = SimklMethod.GET,
        path = PLAYBACK_PATH,
        query = buildMap {
            put("limit", request.limit.toString())
            request.dateFrom?.let { put("date_from", DATE_FROM_FORMAT.format(it)) }

            put("hide_watched", "false")
        },
        authenticated = true,
        retryable = true,
    )

    fun activityStamp(body: String): TrackingActivityStamp? = runCatching {
        val root = JSONObject(body)
        val playback = PLAYBACK_DOMAINS
            .mapNotNull { domain -> instant(root.optJSONObject(domain)?.opt("playback")) }
            .maxOrNull()
        val watched = WATCHED_BUCKETS_BY_DOMAIN
            .flatMap { (domain, buckets) ->
                val block = root.optJSONObject(domain) ?: return@flatMap emptyList()
                buckets.mapNotNull { bucket -> instant(block.opt(bucket)) }
            }
            .maxOrNull()
        TrackingActivityStamp(
            all = instant(root.opt("all")),
            playback = playback,
            watched = watched,
        )
    }.getOrNull()

    fun watchedLookupRequest(queries: List<TrackingWatchedQuery>): SimklRequest = SimklRequest(
        method = SimklMethod.POST,
        path = WATCHED_LOOKUP_PATH,
        query = if (queries.any { it.kind == TrackingMediaKind.SHOW }) {
            mapOf("extended" to EXTENDED_EPISODES)
        } else {
            emptyMap()
        },
        json = watchedLookupBody(queries),
        authenticated = true,
        retryable = true,
        readBudget = SimklReadBudget.LOOKUP,
    )

    fun watchedLookupBody(queries: List<TrackingWatchedQuery>): String {
        val array = JSONArray()
        for (query in queries) {
            array.put(
                JSONObject().apply {
                    put("ids", idsObject(query.externalIds))
                    put("type", if (query.kind == TrackingMediaKind.MOVIE) TYPE_MOVIE else TYPE_SHOW)
                    query.seasonNumber?.let { put("season", it) }
                    query.episodeNumber?.let { put("episode", it) }
                },
            )
        }
        return array.toString()
    }

    private fun idsObject(ids: TrackingExternalIds): JSONObject = JSONObject().apply {
        ids.tmdbId?.let { put("tmdb", it.toString()) }
        ids.imdbId?.let { put("imdb", it) }
        ids.tvdbId?.let { put("tvdb", it.toString()) }
    }

    fun watchedLookupAnswers(body: String, queries: List<TrackingWatchedQuery>): List<TrackingWatchedAnswer>? =
        runCatching {
            val array = JSONArray(body)
            queries.mapIndexed { index, query ->
                val entry = array.optJSONObject(index)
                if (entry == null) {
                    TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
                } else {
                    answerFor(query, entry)
                }
            }
        }.getOrNull()

    private fun answerFor(query: TrackingWatchedQuery, entry: JSONObject): TrackingWatchedAnswer {
        val result = entry.opt("result")
        if (result is String) {
            return if (result.trim().equals(NOT_FOUND, ignoreCase = true)) {
                TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_FOUND)
            } else {
                TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
            }
        }
        if (result !is Boolean) return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        if (!result) {

            return TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        }
        val itemWatchedAt = instant(entry.opt("last_watched_at"))
        return when (query.kind) {
            TrackingMediaKind.MOVIE -> movieAnswer(itemWatchedAt)
            TrackingMediaKind.SHOW -> episodeAnswer(query, entry, itemWatchedAt)
        }
    }

    private fun movieAnswer(itemWatchedAt: Instant?): TrackingWatchedAnswer =
        if (itemWatchedAt != null) {
            TrackingWatchedAnswer(TrackingWatchedVerdict.WATCHED, itemWatchedAt)
        } else {
            TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        }

    private fun episodeAnswer(
        query: TrackingWatchedQuery,
        entry: JSONObject,
        itemWatchedAt: Instant?,
    ): TrackingWatchedAnswer {
        val seasonNumber = query.seasonNumber
        val episodeNumber = query.episodeNumber
        if (seasonNumber == null || episodeNumber == null) {

            return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        }
        val episodes = seasonEntry(entry, seasonNumber)?.optJSONArray(FIELD_EPISODES)
        if (episodes != null) {
            val row = episodeRow(episodes, episodeNumber) ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
            val watched = row.opt(FIELD_WATCHED)
            if (watched !is Boolean) return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
            if (!watched) return TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)

            val episodeAt = instant(row.opt(FIELD_LAST_WATCHED_AT))
                ?: instant(row.opt(FIELD_WATCHED_AT))
                ?: itemWatchedAt
            return TrackingWatchedAnswer(TrackingWatchedVerdict.WATCHED, episodeAt)
        }
        return TrackingWatchedAnswer(
            TrackingWatchedVerdict.WATCHED,
            itemWatchedAt,
        )
    }

    private fun seasonEntry(entry: JSONObject, seasonNumber: Int): JSONObject? {
        val seasons = entry.optJSONArray(FIELD_SEASONS) ?: return null
        for (index in 0 until seasons.length()) {
            val season = seasons.optJSONObject(index) ?: continue
            if (intOrNull(season.opt(FIELD_NUMBER)) == seasonNumber) return season
        }
        return null
    }

    private fun episodeRow(episodes: JSONArray, episodeNumber: Int): JSONObject? {
        for (index in 0 until episodes.length()) {
            val row = episodes.optJSONObject(index) ?: continue
            if (intOrNull(row.opt(FIELD_NUMBER)) == episodeNumber) return row
        }
        return null
    }

    private const val NOT_FOUND = "not_found"

    private const val TYPE_MOVIE = "movie"
    private const val TYPE_SHOW = "show"

    private const val FIELD_SEASONS = "seasons"
    private const val FIELD_EPISODES = "episodes"
    private const val FIELD_NUMBER = "number"
    private const val FIELD_WATCHED = "watched"
    private const val FIELD_LAST_WATCHED_AT = "last_watched_at"
    private const val FIELD_WATCHED_AT = "watched_at"

    fun playbacks(body: String, limit: Int): TrackingPlaybackPage? = runCatching {
        val array = JSONArray(body)
        val items = ArrayList<TrackingSavedPlayback>(array.length())
        var malformed = 0
        for (index in 0 until array.length()) {
            val parsed = parsePlayback(array.optJSONObject(index))
            if (parsed == null) malformed++ else items += parsed
        }
        TrackingPlaybackPage(
            items = items,
            malformed = malformed,

            truncated = array.length() >= limit,
        )
    }.getOrNull()

    private fun parsePlayback(item: JSONObject?): TrackingSavedPlayback? {
        if (item == null) return null
        val type = item.optString("type", "").trim().lowercase()
        val kind = when (type) {
            "movie" -> TrackingMediaKind.MOVIE
            "episode" -> TrackingMediaKind.SHOW
            else -> return null
        }

        val progress = item.opt("progress")
        val percent = doubleOrNull(progress)?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null

        val pausedAt = instant(item.opt("paused_at")) ?: return null
        val recordId = item.opt("id")?.let { value -> scalar(value) }
        return when (kind) {
            TrackingMediaKind.MOVIE -> {
                val movie = item.optJSONObject("movie") ?: return null
                val ids = idsOf(movie) ?: return null
                TrackingSavedPlayback(
                    kind = kind,
                    externalIds = ids,
                    progressPercent = percent,
                    pausedAt = pausedAt,
                    title = movie.optString("title", "").trim().ifEmpty { null },
                    year = intOrNull(movie.opt("year")),
                    providerRecordId = recordId,
                )
            }
            TrackingMediaKind.SHOW -> {

                val container = item.optJSONObject("show") ?: item.optJSONObject("anime") ?: return null
                val ids = idsOf(container) ?: return null
                val episode = item.optJSONObject("episode") ?: return null
                val season = intOrNull(episode.opt("season"))
                val number = intOrNull(episode.opt("number"))
                if (season == null || season <= 0 || number == null || number <= 0) return null
                TrackingSavedPlayback(
                    kind = kind,
                    externalIds = ids,
                    progressPercent = percent,
                    pausedAt = pausedAt,
                    seasonNumber = season,
                    episodeNumber = number,
                    title = container.optString("title", "").trim().ifEmpty { null },
                    year = intOrNull(container.opt("year")),
                    providerRecordId = recordId,
                )
            }
        }
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
        return runCatching { Instant.parse(text) }.getOrNull()
    }

    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim()
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    private fun doubleOrNull(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
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
