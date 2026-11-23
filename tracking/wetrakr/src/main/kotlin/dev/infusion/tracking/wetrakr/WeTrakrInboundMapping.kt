package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingSavedPlayback
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPage
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchedVerdict
import dev.infusion.core.domain.upnext.UpNextTrackerKind
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import dev.infusion.core.domain.upnext.UpNextTrackerRecord
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

object WeTrakrInboundMapping {

    fun activitiesRequest(): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = WeTrakrHttpClient.LAST_ACTIVITIES_PATH,
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    fun playingRequest(target: String, page: Int, limit: Int): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = "${WeTrakrHttpClient.TRACKING_PLAYING_PATH}/$target",
        query = buildMap {
            put("page", page.toString())
            put("limit", limit.toString())
        },
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    fun watchedHistoryRequest(
        target: String,
        cursor: String?,
        since: Instant?,
        limit: Int,
    ): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = "${WeTrakrHttpClient.TRACKING_WATCHED_HISTORY_PATH}/$target",
        query = buildMap {
            put("compact", "true")
            put("limit", limit.toString())
            cursor?.takeIf { it.isNotBlank() }?.let { put("after", it) }
            since?.let { put("from_date", isoTimestamp(it)) }
        },
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    fun upNextRequest(page: Int, limit: Int): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = "${WeTrakrHttpClient.TRACKING_PATH}/$STATUS_WATCHING/$TARGET_SHOWS",
        query = mapOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
            "extended" to EXTENDED_EPISODE_LEVEL_1,
        ),
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    fun externalIdRequest(source: String, externalId: String, kind: TrackingMediaKind): WeTrakrRequest =
        WeTrakrRequest(
            method = WeTrakrMethod.GET,
            path = "${WeTrakrHttpClient.MEDIA_EXTERNAL_PATH}/$source/$externalId",
            query = mapOf("type" to if (kind == TrackingMediaKind.MOVIE) TYPE_MOVIE else TYPE_SHOW),
            appKeyOnly = true,
            limits = WeTrakrHttpClient.READ_LIMITS,
        )

    fun movieHistoryRequest(weTrakrMovieId: Long, page: Int, limit: Int): WeTrakrRequest =
        WeTrakrRequest(
            method = WeTrakrMethod.GET,
            path = "/$TYPE_MOVIES/$weTrakrMovieId/tracking/history",
            query = mapOf("page" to page.toString(), "limit" to limit.toString()),
            authenticated = true,
            limits = WeTrakrHttpClient.READ_LIMITS,
        )

    fun showEpisodeHistoryRequest(weTrakrShowId: Long, page: Int, limit: Int): WeTrakrRequest =
        WeTrakrRequest(
            method = WeTrakrMethod.GET,
            path = "/$TYPE_SHOWS/$weTrakrShowId/tracking/history/unified",
            query = mapOf(
                "filter" to FILTER_EPISODES,
                "page" to page.toString(),
                "limit" to limit.toString(),
            ),
            authenticated = true,
            limits = WeTrakrHttpClient.READ_LIMITS,
        )

    fun activityStamp(body: String): TrackingActivityStamp? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val movies = root.optJSONObject(SECTION_MOVIES)
        val episodes = root.optJSONObject(SECTION_EPISODES)
        val all = instantOrNull(root, "all")
        val sectionAll = newest(listOf(instantOrNull(movies, "all"), instantOrNull(episodes, "all")))
        val watched = newest(
            listOf(
                instantOrNull(movies, STAMP_TRACKING_WATCHED),
                instantOrNull(episodes, STAMP_TRACKING_WATCHED),
            ),
        )
        if (all == null && sectionAll == null && watched == null) return null
        return TrackingActivityStamp(
            all = all,
            playback = sectionAll ?: all,
            watched = watched,
        )
    }

    fun playbacks(
        body: String,
        target: String,
        pageCount: Int?,
        page: Int,
        limit: Int,
    ): TrackingPlaybackPage? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val items = ArrayList<TrackingSavedPlayback>(array.length())
        var malformed = 0
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
            if (entry == null) {
                malformed++
                continue
            }
            val parsed = if (target == TARGET_EPISODES) episodePlayback(entry) else moviePlayback(entry)
            if (parsed == null) malformed++ else items += parsed
        }
        return TrackingPlaybackPage(
            items = items,
            malformed = malformed,

            truncated = pageCount?.let { page < it } ?: (array.length() >= limit),
        )
    }

    private fun moviePlayback(entry: JSONObject): TrackingSavedPlayback? {
        val ids = externalIds(entry.optJSONObject(FIELD_IDS))
        if (ids.isEmpty) return null
        val playback = entry.optJSONObject(FIELD_PLAYBACK) ?: return null
        val progress = playback.optDouble("progress_percent", Double.NaN).takeUnless { it.isNaN() } ?: return null
        if (progress !in 0.0..100.0) return null
        return TrackingSavedPlayback(
            kind = TrackingMediaKind.MOVIE,
            externalIds = ids,
            progressPercent = progress,
            pausedAt = instantOrNull(playback, "tracked_at"),
            title = entry.optStringOrNull("title"),
            year = entry.optIntOrNull("year"),
            providerRecordId = entry.optLongOrNull("id")?.toString(),
        )
    }

    private fun episodePlayback(entry: JSONObject): TrackingSavedPlayback? {
        val show = entry.optJSONObject(FIELD_SHOW) ?: return null
        val ids = externalIds(show.optJSONObject(FIELD_IDS))
        if (ids.isEmpty) return null
        val season = entry.optIntOrNull("season_number") ?: return null
        val number = entry.optIntOrNull("number") ?: return null
        if (season <= 0 || number <= 0) return null
        val playback = entry.optJSONObject(FIELD_PLAYBACK) ?: return null
        val progress = playback.optDouble("progress_percent", Double.NaN).takeUnless { it.isNaN() } ?: return null
        if (progress !in 0.0..100.0) return null
        return TrackingSavedPlayback(
            kind = TrackingMediaKind.SHOW,
            externalIds = ids,
            progressPercent = progress,
            pausedAt = instantOrNull(playback, "tracked_at"),
            seasonNumber = season,
            episodeNumber = number,
            title = show.optStringOrNull("title"),
            year = show.optIntOrNull("year"),
            providerRecordId = entry.optLongOrNull("id")?.toString(),
        )
    }

    fun watchedHistoryPage(
        body: String,
        unit: TrackingWatchedHistoryUnit,
        nextCursor: String?,
        readStartedAt: Instant,
    ): TrackingWatchedHistoryPage? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val records = ArrayList<TrackingWatchedHistoryRecord>(array.length())
        var malformed = 0
        var undated = 0
        var showLevelOnly = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            if (row == null) {
                malformed++
                continue
            }
            when (unit) {
                TrackingWatchedHistoryUnit.MOVIE -> {
                    val ids = externalIds(row.optJSONObject(FIELD_IDS))
                    if (ids.isEmpty) {
                        malformed++
                        continue
                    }
                    if (row.optBoolean("watched_at_unknown", false)) {
                        undated++
                        continue
                    }
                    val watchedAt = instantOrNull(row, FIELD_WATCHED_AT)
                    if (watchedAt == null) {
                        undated++
                        continue
                    }
                    records += TrackingWatchedHistoryRecord(
                        unit = TrackingWatchedHistoryUnit.MOVIE,
                        externalIds = ids,
                        watchedAt = watchedAt,
                        title = row.optStringOrNull("title"),
                        year = row.optIntOrNull("year"),
                    )
                }
                TrackingWatchedHistoryUnit.EPISODE -> {

                    val ids = externalIds(row.optJSONObject(FIELD_SHOW_IDS))
                        .takeUnless { it.isEmpty }
                        ?: externalIds(row.optJSONObject(FIELD_SHOW)?.optJSONObject(FIELD_IDS))
                    if (ids.isEmpty) {
                        malformed++
                        continue
                    }
                    val season = row.optIntOrNull("season_number")
                    val number = row.optIntOrNull("number")
                    if (season == null || number == null || season <= 0 || number <= 0) {
                        showLevelOnly++
                        continue
                    }
                    if (row.optBoolean("watched_at_unknown", false)) {
                        undated++
                        continue
                    }
                    val watchedAt = instantOrNull(row, FIELD_WATCHED_AT)
                    if (watchedAt == null) {
                        undated++
                        continue
                    }
                    records += TrackingWatchedHistoryRecord(
                        unit = TrackingWatchedHistoryUnit.EPISODE,
                        externalIds = ids,
                        seasonNumber = season,
                        episodeNumber = number,
                        watchedAt = watchedAt,

                        title = row.optJSONObject(FIELD_SHOW)?.optStringOrNull("title"),
                        year = row.optJSONObject(FIELD_SHOW)?.optIntOrNull("year"),
                    )
                }
            }
        }
        return TrackingWatchedHistoryPage(
            records = records,
            next = nextCursor?.takeIf { it.isNotBlank() },
            malformed = malformed,
            undated = undated,
            showLevelOnly = showLevelOnly,

            alreadyCovered = 0,

            truncating = !nextCursor.isNullOrBlank(),
            completedThrough = if (nextCursor.isNullOrBlank()) readStartedAt else null,
        )
    }

    fun upNextPage(body: String, pageCount: Int?, page: Int, limit: Int): UpNextTrackerPage? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val records = ArrayList<UpNextTrackerRecord>(array.length())
        var malformed = 0
        var skipped = 0
        for (index in 0 until array.length()) {
            val show = array.optJSONObject(index)
            if (show == null) {
                malformed++
                continue
            }
            val ids = externalIds(show.optJSONObject(FIELD_IDS))
            if (ids.isEmpty) {
                malformed++
                continue
            }
            val next = show.optJSONObject(FIELD_NEXT_WATCHING_EPISODE)
            if (next == null) {
                skipped++
                continue
            }
            val season = next.optIntOrNull("season_number")
            val number = next.optIntOrNull("number")
            if (season == null || number == null || season <= 0 || number <= 0) {
                malformed++
                continue
            }
            records += UpNextTrackerRecord(
                kind = UpNextTrackerKind.EPISODE,
                externalIds = ids,
                seasonNumber = season,
                episodeNumber = number,
                episodeTitle = next.optStringOrNull("title"),
                seriesTitle = show.optStringOrNull("title"),
                year = show.optIntOrNull("year"),

                activityAt = instantOrNull(show, FIELD_TRACKED_AT),
                airedAt = localDateOrNull(next, FIELD_AIR_DATE),
                airedAtProvenAt = instantOrNull(next, FIELD_AIR_DATETIME),
                providerRecordId = show.optLongOrNull("id")?.toString(),
            )
        }
        return UpNextTrackerPage(
            records = records,
            malformed = malformed,
            skipped = skipped,
            truncated = pageCount?.let { page < it } ?: (array.length() >= limit),
        )
    }

    fun externalIdOf(body: String): Long? =
        runCatching { JSONObject(body).optLongOrNull("id") }.getOrNull()?.takeIf { it > 0 }

    fun movieWatchedAnswer(body: String): TrackingWatchedAnswer {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        val tracks = tracksOf(root)
            ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        if (tracks.isEmpty()) return TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        return TrackingWatchedAnswer(
            verdict = TrackingWatchedVerdict.WATCHED,
            watchedAt = newestPlayInstant(tracks),
        )
    }

    fun episodeWatchedAnswer(
        body: String,
        seasonNumber: Int,
        episodeNumber: Int,
        hasMorePages: Boolean,
    ): TrackingWatchedAnswer {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        val tracks = tracksOf(root)
            ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        val matching = tracks.filter { track ->
            track.optString("target", "").trim() == TARGET_EPISODE &&
                track.optIntOrNull("season_number") == seasonNumber &&
                track.optIntOrNull("number") == episodeNumber
        }
        if (matching.isNotEmpty()) {
            return TrackingWatchedAnswer(
                verdict = TrackingWatchedVerdict.WATCHED,
                watchedAt = newestPlayInstant(matching),
            )
        }
        return if (hasMorePages) {
            TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        } else {
            TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        }
    }

    fun hasMorePages(body: String, page: Int): Boolean {
        val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: return false
        val totalPages = meta.optIntOrNull("totalPages") ?: return false
        return totalPages > page
    }

    private fun tracksOf(root: JSONObject): List<JSONObject>? {
        val data = root.optJSONArray(FIELD_DATA)
        if (data == null) return metaOnlyEmptyPage(root)
        val tracks = ArrayList<JSONObject>()
        for (index in 0 until data.length()) {
            val day = data.optJSONObject(index) ?: continue
            val rows = day.optJSONArray("tracks") ?: continue
            for (row in 0 until rows.length()) {
                rows.optJSONObject(row)?.let { tracks += it }
            }
        }

        if (data.length() > 0 && tracks.isEmpty()) return null
        return tracks
    }

    private fun metaOnlyEmptyPage(root: JSONObject): List<JSONObject>? {
        val meta = root.optJSONObject(FIELD_META) ?: return null

        if (meta.optInt(FIELD_TOTAL, 0) > 0) return null
        return emptyList()
    }

    private fun newestPlayInstant(tracks: List<JSONObject>): Instant? = newest(
        tracks.map { track ->
            if (track.optBoolean("tracked_at_unknown", false)) null else instantOrNull(track, FIELD_TRACKED_AT)
        },
    )

    fun externalIds(json: JSONObject?): TrackingExternalIds {
        if (json == null) return TrackingExternalIds()
        return TrackingExternalIds(
            tmdbId = json.optLongOrNull("tmdb")?.takeIf { it > 0 },
            imdbId = json.optStringOrNull("imdb")?.trim()?.takeIf { it.isNotEmpty() },
            tvdbId = json.optLongOrNull("tvdb")?.takeIf { it > 0 },
        )
    }

    private fun newest(values: Iterable<Instant?>): Instant? = values.filterNotNull().maxOrNull()

    private fun instantOrNull(json: JSONObject?, key: String): Instant? =
        json?.optStringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun localDateOrNull(json: JSONObject, key: String): LocalDate? {
        val raw = json.optStringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { LocalDate.parse(raw) }.getOrNull()?.let { return it }
        return runCatching { Instant.parse(raw).atOffset(java.time.ZoneOffset.UTC).toLocalDate() }.getOrNull()
    }

    private fun isoTimestamp(instant: Instant): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)

    fun pageCount(headers: Map<String, String>): Int? = headers.entries
        .firstOrNull { it.key.equals("X-Pagination-Page-Count", ignoreCase = true) }
        ?.value?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    fun nextCursor(headers: Map<String, String>): String? = headers.entries
        .firstOrNull { it.key.equals("X-Pagination-Next", ignoreCase = true) }
        ?.value?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

    private const val SECTION_MOVIES = "movies"
    private const val SECTION_EPISODES = "episodes"
    private const val STAMP_TRACKING_WATCHED = "last_tracking_watched_at"

    internal const val TARGET_MOVIES = "movies"
    internal const val TARGET_EPISODES = "episodes"
    internal const val TARGET_EPISODE = "episode"
    internal const val TARGET_SHOWS = "shows"
    private const val TYPE_MOVIES = "movies"
    private const val TYPE_MOVIE = "movie"
    private const val TYPE_SHOWS = "shows"
    private const val TYPE_SHOW = "show"
    private const val STATUS_WATCHING = "watching"
    private const val FILTER_EPISODES = "episodes"
    private const val EXTENDED_EPISODE_LEVEL_1 = "episode_level_1"

    private const val FIELD_IDS = "ids"
    private const val FIELD_DATA = "data"
    private const val FIELD_META = "meta"
    private const val FIELD_TOTAL = "total"
    private const val FIELD_SHOW = "show"
    private const val FIELD_SHOW_IDS = "show_ids"
    private const val FIELD_PLAYBACK = "playback"
    private const val FIELD_WATCHED_AT = "watched_at"
    private const val FIELD_TRACKED_AT = "tracked_at"
    private const val FIELD_AIR_DATE = "air_date"
    private const val FIELD_AIR_DATETIME = "air_datetime"
    private const val FIELD_NEXT_WATCHING_EPISODE = "next_watching_episode"
}
