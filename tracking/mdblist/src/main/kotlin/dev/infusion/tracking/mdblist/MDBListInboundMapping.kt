package dev.infusion.tracking.mdblist

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

object MDBListInboundMapping {

    const val ACTIVITIES_PATH: String = "/sync/last_activities"

    const val PLAYBACK_PATH: String = "/sync/playback"

    const val HISTORY_SEGMENT: String = "/sync/history"

    const val STATE_SEGMENT: String = "/sync/state"

    fun activitiesRequest(): MDBListRequest = MDBListRequest(
        method = MDBListMethod.GET,
        path = ACTIVITIES_PATH,
        authenticated = true,
        retryable = true,
    )

    fun playbackRequest(request: TrackingPlaybackRequest): MDBListRequest = MDBListRequest(
        method = MDBListMethod.GET,
        path = PLAYBACK_PATH,
        authenticated = true,
        retryable = true,
        readBudget = MDBListReadBudget.LOOKUP,
    )

    fun showHistoryRequest(showTmdbId: Long): MDBListRequest = MDBListRequest(
        method = MDBListMethod.GET,
        path = "$HISTORY_SEGMENT/show/tmdb/$showTmdbId",
        authenticated = true,
        retryable = true,
        readBudget = MDBListReadBudget.LOOKUP,
    )

    fun movieStateRequest(ids: List<Long>): MDBListRequest = MDBListRequest(
        method = MDBListMethod.POST,
        path = "$STATE_SEGMENT/movie/tmdb",
        json = JSONObject().put("ids", JSONArray(ids)).toString(),
        authenticated = true,
        retryable = true,
        readBudget = MDBListReadBudget.LOOKUP,
    )

    fun activityStamp(body: String): TrackingActivityStamp? = runCatching {
        val root = JSONObject(body)
        TrackingActivityStamp(
            all = instant(root.opt("server_time")),
            playback = newest(instant(root.opt("paused_at")), instant(root.opt("episode_paused_at"))),
            watched = newest(instant(root.opt("watched_at")), instant(root.opt("episode_watched_at"))),
        )
    }.getOrNull()

    private fun newest(first: Instant?, second: Instant?): Instant? = when {
        first == null -> second
        second == null -> first
        first.isAfter(second) -> first
        else -> second
    }

    fun playbacks(body: String): TrackingPlaybackPage? = runCatching {
        val array = JSONArray(body)
        val items = ArrayList<TrackingSavedPlayback>(array.length())
        var malformed = 0
        for (index in 0 until array.length()) {
            val parsed = parsePlayback(array.optJSONObject(index))
            if (parsed == null) malformed++ else items += parsed
        }
        TrackingPlaybackPage(items = items, malformed = malformed, truncated = false)
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
        val observedAt = instant(item.opt("paused_at"))
            ?: instantFromEpoch(item.opt("updated_at_ts"))
            ?: instant(item.opt("updated_at"))
            ?: return null
        val recordId = item.opt("id")?.let { value -> scalar(value) }
        return when (kind) {
            TrackingMediaKind.MOVIE -> {
                val movie = item.optJSONObject("movie") ?: return null
                val ids = idsOf(movie) ?: return null
                TrackingSavedPlayback(
                    kind = kind,
                    externalIds = ids,
                    progressPercent = percent,
                    pausedAt = observedAt,
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
                    pausedAt = observedAt,
                    seasonNumber = season,
                    episodeNumber = number,
                    title = show.optString("title", "").trim().ifEmpty { null },
                    year = intOrNull(show.opt("year")),
                    providerRecordId = recordId,
                )
            }
        }
    }

    fun episodeWatched(body: String, seasonNumber: Int, episodeNumber: Int): TrackingWatchedAnswer? =
        runCatching {
            val root = JSONObject(body)
            val plays = root.optJSONArray("plays") ?: return@runCatching null
            var newest: Instant? = null
            for (index in 0 until plays.length()) {
                val row = plays.optJSONObject(index) ?: continue
                val season = intOrNull(row.opt("season_num"))
                val number = intOrNull(row.opt("episode_num"))
                if (season != seasonNumber || number != episodeNumber) continue
                val at = instant(row.opt("watched_at"))
                if (at != null && (newest == null || at.isAfter(newest))) newest = at
                if (at == null) {

                    return@runCatching null
                }
            }
            if (newest != null) return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.WATCHED, newest)
            if (root.optBoolean("truncated", false)) return@runCatching null
            TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        }.getOrNull()

    fun movieWatched(body: String, id: Long): TrackingWatchedAnswer? = runCatching {
        val root = JSONObject(body)
        val wanted = id.toString()
        root.optJSONArray("not_found")?.let { missing ->
            for (index in 0 until missing.length()) {
                val value = scalar(missing.opt(index)) ?: continue
                if (value == wanted) return@runCatching NOT_FOUND_ANSWER
            }
        }
        val items = root.optJSONArray("items") ?: return@runCatching null
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            if (scalar(row.opt("id")) != wanted) continue
            val watched = row.opt("watched")
            if (watched !is Boolean) return@runCatching null
            if (!watched) return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)

            val at = instant(row.opt("last_watched_at")) ?: return@runCatching null
            return@runCatching TrackingWatchedAnswer(TrackingWatchedVerdict.WATCHED, at)
        }
        null
    }.getOrNull()

    private val NOT_FOUND_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_FOUND)

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

    private fun instantFromEpoch(value: Any?): Instant? {
        val seconds = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        } ?: return null
        return runCatching { Instant.ofEpochSecond(seconds) }.getOrNull()
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
