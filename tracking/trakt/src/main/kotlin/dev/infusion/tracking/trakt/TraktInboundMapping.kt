package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingPlaybackRequest
import dev.infusion.core.domain.tracking.TrackingSavedPlayback
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedVerdict
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

object TraktInboundMapping {

    const val ACTIVITIES_PATH: String = "/sync/last_activities"

    const val PLAYBACK_PATH: String = "/sync/playback"

    const val SHOW_PROGRESS_SEGMENT: String = "/shows"

    const val USER_HISTORY_SEGMENT: String = "/users/me/history/movies"

    fun activitiesRequest(): TraktRequest = TraktRequest(
        method = TraktMethod.GET,
        path = ACTIVITIES_PATH,
        authenticated = true,
        retryable = true,
    )

    fun playbackRequest(request: TrackingPlaybackRequest, page: Int): TraktRequest = TraktRequest(
        method = TraktMethod.GET,
        path = PLAYBACK_PATH,
        query = mapOf(
            "limit" to request.limit.toString(),
            "page" to page.coerceAtLeast(1).toString(),
        ),
        authenticated = true,
        retryable = true,
    )

    fun activityStamp(body: String): TrackingActivityStamp? = runCatching {
        val root = JSONObject(body)
        val movies = root.optJSONObject("movies")
        val episodes = root.optJSONObject("episodes")
        TrackingActivityStamp(
            all = instant(root.opt("all")),
            playback = newest(instant(movies?.opt("paused_at")), instant(episodes?.opt("paused_at"))),
            watched = newest(instant(movies?.opt("watched_at")), instant(episodes?.opt("watched_at"))),
        )
    }.getOrNull()

    private fun newest(first: Instant?, second: Instant?): Instant? = when {
        first == null -> second
        second == null -> first
        first.isAfter(second) -> first
        else -> second
    }

    fun playbacks(
        body: String,
        limit: Int,
        page: Int = 1,
        pageCount: Int? = null,
    ): TrackingPlaybackPage? = runCatching {
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

            truncated = pageCount?.let { it > page } ?: (array.length() >= limit),
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

        val percent = doubleOrNull(item.opt("progress"))
            ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null

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

                val show = item.optJSONObject("show") ?: return null
                val ids = idsOf(show) ?: return null
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
                    title = show.optString("title", "").trim().ifEmpty { null },
                    year = intOrNull(show.opt("year")),
                    providerRecordId = recordId,
                )
            }
        }
    }

    fun showProgressRequest(showId: String): TraktRequest = TraktRequest(
        method = TraktMethod.GET,
        path = "$SHOW_PROGRESS_SEGMENT/${encodePathSegment(showId)}/progress/watched",
        query = mapOf("hidden" to "true", "specials" to "true"),
        authenticated = true,
        retryable = true,
        readBudget = TraktReadBudget.LOOKUP,
    )

    fun movieHistoryRequest(movieId: String): TraktRequest = TraktRequest(
        method = TraktMethod.GET,
        path = "$USER_HISTORY_SEGMENT/${encodePathSegment(movieId)}",
        query = mapOf("limit" to LOOKUP_LIMIT.toString()),
        authenticated = true,
        retryable = true,
        readBudget = TraktReadBudget.LOOKUP,
    )

    fun episodeWatched(body: String, seasonNumber: Int, episodeNumber: Int): TrackingWatchedAnswer? =
        runCatching {
            val root = JSONObject(body)
            val seasons = root.optJSONArray("seasons") ?: return@runCatching null
            val itemWatchedAt = instant(root.opt("last_watched_at"))
            val season = seasonRow(seasons, seasonNumber)
                ?: return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
            val episodes = season.optJSONArray("episodes")
                ?: return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
            val row = episodeRow(episodes, episodeNumber)
                ?: return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
            val completed = row.opt("completed")
            if (completed !is Boolean) return@runCatching null
            if (!completed) return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
            TrackingWatchedAnswer(
                TrackingWatchedVerdict.WATCHED,
                instant(row.opt("last_watched_at")) ?: itemWatchedAt,
            )
        }.getOrNull()

    fun movieWatched(body: String): TrackingWatchedAnswer? = runCatching {
        val array = JSONArray(body)
        if (array.length() == 0) {
            return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        }
        var newest: Instant? = null
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val at = instant(entry.opt("watched_at")) ?: continue
            if (newest == null || at.isAfter(newest)) newest = at
        }
        TrackingWatchedAnswer(TrackingWatchedVerdict.WATCHED, newest)
    }.getOrNull()

    private fun seasonRow(seasons: JSONArray, seasonNumber: Int): JSONObject? {
        for (index in 0 until seasons.length()) {
            val season = seasons.optJSONObject(index) ?: continue
            if (intOrNull(season.opt("number")) == seasonNumber) return season
        }
        return null
    }

    private fun episodeRow(episodes: JSONArray, episodeNumber: Int): JSONObject? {
        for (index in 0 until episodes.length()) {
            val row = episodes.optJSONObject(index) ?: continue
            if (intOrNull(row.opt("number")) == episodeNumber) return row
        }
        return null
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

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value.trim(), "UTF-8")

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

    private const val LOOKUP_LIMIT: Int = 25
}
