package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingChangeFeedCategory
import dev.infusion.core.domain.tracking.TrackingChangeFeedEntry
import dev.infusion.core.domain.tracking.TrackingChangeFeedPage
import dev.infusion.core.domain.tracking.TrackingChangeFeedStatus
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingSavedPlayback
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPage
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryProviderCount
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryProviderCounts
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryResidueRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchedItemName
import dev.infusion.core.domain.tracking.TrackingWatchedItemNamesPage
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

    fun watchedNamesRequest(target: String, page: Int, limit: Int): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = "${WeTrakrHttpClient.TRACKING_WATCHED_PATH}/$target",
        query = mapOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
        ),
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    fun watchedNamesPage(
        body: String,
        page: Int,
        limit: Int,
        headers: Map<String, String>,
    ): TrackingWatchedItemNamesPage? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val names = ArrayList<TrackingWatchedItemName>(array.length())
        var malformed = 0
        var titled = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            if (row == null) {
                malformed++
                continue
            }
            val ids = externalIds(row.optJSONObject(FIELD_IDS))
            if (ids.isEmpty) {
                malformed++
                continue
            }
            val title = row.optStringOrNull("title")?.trim().orEmpty()
            if (title.isEmpty()) continue
            titled++
            names += TrackingWatchedItemName(
                externalIds = ids,
                title = title,

                year = yearOf(row, "release_date") ?: yearOf(row, "first_air_date"),
            )
        }
        val pageCount = pageCount(headers)
        return TrackingWatchedItemNamesPage(
            names = names,
            rows = array.length(),
            titled = titled,
            malformed = malformed,
            page = page,

            hasMore = if (pageCount != null) page < pageCount else array.length() >= limit,
        )
    }

    private fun yearOf(row: JSONObject, key: String): Int? =
        row.optStringOrNull(key)?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2200 }

    fun watchedCountersRequest(): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = WeTrakrHttpClient.TRACKING_WATCHED_COUNTERS_PATH,
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

        val residue = ArrayList<TrackingWatchedHistoryResidueRecord>()
        var malformed = 0
        var undated = 0
        var showLevelOnly = 0
        var seasonZero = 0
        var coordinateMissing = 0
        var coordinateInvalid = 0
        var coordinateNegative = 0
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
                        residue += undatedResidue(TrackingWatchedHistoryUnit.MOVIE, ids, null, null)
                        continue
                    }
                    val watchedAt = instantOrNull(row, FIELD_WATCHED_AT)
                    if (watchedAt == null) {
                        undated++
                        residue += undatedResidue(TrackingWatchedHistoryUnit.MOVIE, ids, null, null)
                        continue
                    }
                    records += TrackingWatchedHistoryRecord(
                        unit = TrackingWatchedHistoryUnit.MOVIE,
                        externalIds = ids,
                        watchedAt = watchedAt,
                        title = row.optStringOrNull("title"),
                        year = row.optIntOrNull("year"),
                        providerPlayId = playIdOf(row),
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
                        val coordinateClass = when {
                            classifyCoordinate(row, FIELD_SEASON_NUMBER) != CoordinateClass.OK ->
                                classifyCoordinate(row, FIELD_SEASON_NUMBER)
                            else -> classifyCoordinate(row, FIELD_NUMBER)
                        }
                        when (coordinateClass) {
                            CoordinateClass.SEASON_ZERO -> seasonZero++
                            CoordinateClass.MISSING -> coordinateMissing++
                            CoordinateClass.INVALID -> coordinateInvalid++
                            CoordinateClass.NEGATIVE -> coordinateNegative++
                            CoordinateClass.OK -> coordinateMissing++
                        }
                        residue += TrackingWatchedHistoryResidueRecord(
                            externalIds = ids,
                            kind = coordinateClass.kind,

                            seasonNumber = rawIntOrNull(row, FIELD_SEASON_NUMBER),
                            episodeNumber = rawIntOrNull(row, FIELD_NUMBER),
                        )
                        continue
                    }
                    if (row.optBoolean("watched_at_unknown", false)) {
                        undated++
                        residue += undatedResidue(TrackingWatchedHistoryUnit.EPISODE, ids, season, number)
                        continue
                    }
                    val watchedAt = instantOrNull(row, FIELD_WATCHED_AT)
                    if (watchedAt == null) {
                        undated++
                        residue += undatedResidue(TrackingWatchedHistoryUnit.EPISODE, ids, season, number)
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
                        providerPlayId = playIdOf(row),
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

            residue = residue,
            seasonZero = seasonZero,
            coordinateMissing = coordinateMissing,
            coordinateInvalid = coordinateInvalid,
            coordinateNegative = coordinateNegative,
        )
    }

    private fun undatedResidue(
        unit: TrackingWatchedHistoryUnit,
        ids: TrackingExternalIds,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): TrackingWatchedHistoryResidueRecord = TrackingWatchedHistoryResidueRecord(
        externalIds = ids,
        kind = TrackingWatchedHistoryResidueRecord.KIND_UNDATED,
        seasonNumber = if (unit == TrackingWatchedHistoryUnit.EPISODE) seasonNumber else null,
        episodeNumber = if (unit == TrackingWatchedHistoryUnit.EPISODE) episodeNumber else null,
    )

    private fun classifyCoordinate(row: JSONObject, key: String): CoordinateClass {
        if (!row.has(key) || row.isNull(key)) return CoordinateClass.MISSING
        val text = row.opt(key)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return CoordinateClass.MISSING
        val value = text.toIntOrNull() ?: return CoordinateClass.INVALID
        return when {
            value == 0 -> CoordinateClass.SEASON_ZERO
            value < 0 -> CoordinateClass.NEGATIVE
            else -> CoordinateClass.OK
        }
    }

    private fun rawIntOrNull(row: JSONObject, key: String): Int? {
        if (!row.has(key) || row.isNull(key)) return null
        return row.opt(key)?.toString()?.trim()?.toIntOrNull()
    }

    private enum class CoordinateClass(val kind: String) {

        OK(TrackingWatchedHistoryResidueRecord.KIND_COORDINATE_MISSING),
        SEASON_ZERO(TrackingWatchedHistoryResidueRecord.KIND_SEASON_ZERO),
        MISSING(TrackingWatchedHistoryResidueRecord.KIND_COORDINATE_MISSING),
        INVALID(TrackingWatchedHistoryResidueRecord.KIND_COORDINATE_INVALID),
        NEGATIVE(TrackingWatchedHistoryResidueRecord.KIND_COORDINATE_NEGATIVE),
    }

    private fun playIdOf(row: JSONObject): String? =
        row.optStringOrNull(FIELD_PLAY_ID)?.trim()?.takeIf { it.isNotEmpty() }

    fun watchedCounters(body: String, observedAt: Instant): TrackingWatchedHistoryProviderCounts? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val movies = counterOrNull(root, FIELD_COUNTER_MOVIES)
        val episodes = counterOrNull(root, FIELD_COUNTER_EPISODES)
        if (movies == null && episodes == null) return null
        return TrackingWatchedHistoryProviderCounts(
            movies = TrackingWatchedHistoryProviderCount(items = movies),
            episodes = TrackingWatchedHistoryProviderCount(items = episodes),
            observedAt = observedAt,
        )
    }

    private fun counterOrNull(root: JSONObject, key: String): Int? {
        if (!root.has(key) || root.isNull(key)) return null
        return root.optInt(key, -1).takeIf { it >= 0 }
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

                activityAt = instantOrNull(show, FIELD_WATCHED_AT) ?: instantOrNull(show, FIELD_TRACKED_AT),
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

    fun journalRequest(fromDate: java.time.Instant, page: Int, limit: Int): WeTrakrRequest =
        WeTrakrRequest(
            method = WeTrakrMethod.GET,
            path = WeTrakrHttpClient.JOURNAL_PATH,
            query = buildMap {
                put("from_date", isoTimestamp(fromDate))
                put("page", page.toString())
                put("limit", limit.toString())
            },
            authenticated = true,
            limits = WeTrakrHttpClient.READ_LIMITS,
        )

    fun journalPage(
        body: String,
        headers: Map<String, String>,
        page: Int,
        limit: Int,
    ): TrackingChangeFeedPage? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val visibleUntil = instantOrNull(root, FIELD_VISIBLE_UNTIL) ?: return null
        val array = root.optJSONArray(FIELD_JOURNAL) ?: return null
        val entries = ArrayList<TrackingChangeFeedEntry>(array.length())
        var malformed = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            if (row == null) {
                malformed++
                continue
            }
            val entry = journalEntry(row)
            if (entry == null) malformed++ else entries += entry
        }
        return TrackingChangeFeedPage(
            entries = entries,
            visibleUntil = visibleUntil,
            retentionDays = counterOrNull(root, FIELD_RETENTION_DAYS),
            malformed = malformed,

            truncating = pageCount(headers)?.let { page < it } ?: (array.length() >= limit),
        )
    }

    private fun journalEntry(row: JSONObject): TrackingChangeFeedEntry? {
        val entryId = row.optStringOrNull(FIELD_ENTRY_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val category = categoryOf(row.optStringOrNull(FIELD_CATEGORY)) ?: return null
        val status = statusOf(row.optStringOrNull(FIELD_STATUS_JOURNAL)) ?: return null
        val actionAt = instantOrNull(row, FIELD_ACTION_AT) ?: return null
        val type = row.optStringOrNull(FIELD_TYPE)?.trim()?.lowercase()
        val unit = when (type) {
            TYPE_MOVIE -> TrackingWatchedHistoryUnit.MOVIE
            TYPE_EPISODE -> TrackingWatchedHistoryUnit.EPISODE

            else -> null
        }
        val externalIds = externalIds(row.optJSONObject(FIELD_IDS))
        val watchedAt = if (row.optBoolean(FIELD_WATCHED_AT_UNKNOWN, false)) {
            null
        } else {
            instantOrNull(row, FIELD_WATCHED_AT)
        }
        return when (unit) {
            TrackingWatchedHistoryUnit.MOVIE -> TrackingChangeFeedEntry(
                entryId = entryId,
                category = category,
                status = status,
                actionAt = actionAt,
                unit = TrackingWatchedHistoryUnit.MOVIE,
                externalIds = externalIds,
                watchedAt = watchedAt,
                watchedAtUnknown = row.optBoolean(FIELD_WATCHED_AT_UNKNOWN, false),
                providerPlayId = playIdOf(row),
                title = row.optStringOrNull(FIELD_TITLE),
            )
            TrackingWatchedHistoryUnit.EPISODE -> {
                val season = row.optIntOrNull(FIELD_SEASON_NUMBER)
                val number = row.optIntOrNull(FIELD_NUMBER)
                if (season == null || number == null || season <= 0 || number <= 0) return null
                TrackingChangeFeedEntry(
                    entryId = entryId,
                    category = category,
                    status = status,
                    actionAt = actionAt,
                    unit = TrackingWatchedHistoryUnit.EPISODE,
                    externalIds = externalIds,

                    seriesExternalIds = externalIds(row.optJSONObject(FIELD_SHOW_IDS)),
                    seasonNumber = season,
                    episodeNumber = number,
                    watchedAt = watchedAt,
                    watchedAtUnknown = row.optBoolean(FIELD_WATCHED_AT_UNKNOWN, false),
                    providerPlayId = playIdOf(row),
                    title = row.optStringOrNull(FIELD_TITLE),
                )
            }
            null -> TrackingChangeFeedEntry(
                entryId = entryId,
                category = category,
                status = status,
                actionAt = actionAt,
                unit = null,
                externalIds = externalIds,
                watchedAt = watchedAt,
                watchedAtUnknown = row.optBoolean(FIELD_WATCHED_AT_UNKNOWN, false),
                providerPlayId = playIdOf(row),
                title = row.optStringOrNull(FIELD_TITLE),
            )
        }
    }

    private fun categoryOf(raw: String?): TrackingChangeFeedCategory? = when (raw?.trim()?.lowercase()) {
        "watched" -> TrackingChangeFeedCategory.WATCHED
        "watching" -> TrackingChangeFeedCategory.WATCHING
        "waiting" -> TrackingChangeFeedCategory.WAITING
        "planning" -> TrackingChangeFeedCategory.PLANNING
        "dropped" -> TrackingChangeFeedCategory.DROPPED
        "paused" -> TrackingChangeFeedCategory.PAUSED
        "ignored" -> TrackingChangeFeedCategory.IGNORED
        "ratings" -> TrackingChangeFeedCategory.RATINGS
        "favorites" -> TrackingChangeFeedCategory.FAVORITES
        "notes" -> TrackingChangeFeedCategory.NOTES
        "likes" -> TrackingChangeFeedCategory.LIKES
        "comments" -> TrackingChangeFeedCategory.COMMENTS
        "lists" -> TrackingChangeFeedCategory.LISTS
        else -> null
    }

    private fun statusOf(raw: String?): TrackingChangeFeedStatus? = when (raw?.trim()?.lowercase()) {
        "added" -> TrackingChangeFeedStatus.ADDED
        "updated" -> TrackingChangeFeedStatus.UPDATED
        "removed" -> TrackingChangeFeedStatus.REMOVED
        else -> null
    }

    fun externalIdOf(body: String): Long? =
        runCatching { JSONObject(body).optLongOrNull("id") }.getOrNull()?.takeIf { it > 0 }

    fun movieWatchedAnswer(body: String): TrackingWatchedAnswer {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        val tracks = tracksOf(root)
            ?: return TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)

        val plays = watchedPlays(tracks)
        if (plays.isEmpty()) return TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
        return TrackingWatchedAnswer(
            verdict = TrackingWatchedVerdict.WATCHED,
            watchedAt = newestPlayInstant(plays),
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
        val matching = watchedPlays(tracks).filter { track ->
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

    private fun watchedPlays(tracks: List<JSONObject>): List<JSONObject> = tracks.filter { track ->
        val status = track.optStringOrNull(FIELD_STATUS)?.trim()
        status.isNullOrEmpty() || status.equals(STATUS_WATCHED, ignoreCase = true)
    }

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

    private const val FIELD_PLAY_ID = "play_id"

    private const val FIELD_COUNTER_MOVIES = "movies"
    private const val FIELD_COUNTER_EPISODES = "episodes"
    private const val FIELD_TRACKED_AT = "tracked_at"
    private const val FIELD_STATUS = "status"

    private const val STATUS_WATCHED = "watched"
    private const val FIELD_AIR_DATE = "air_date"
    private const val FIELD_AIR_DATETIME = "air_datetime"
    private const val FIELD_NEXT_WATCHING_EPISODE = "next_watching_episode"

    private const val FIELD_JOURNAL = "journal"
    private const val FIELD_VISIBLE_UNTIL = "visible_until"
    private const val FIELD_RETENTION_DAYS = "retention_days"
    private const val FIELD_ENTRY_ID = "entry_id"
    private const val FIELD_CATEGORY = "category"

    private const val FIELD_STATUS_JOURNAL = "status"
    private const val FIELD_ACTION_AT = "action_at"
    private const val FIELD_TYPE = "type"
    private const val FIELD_TITLE = "title"
    private const val FIELD_SEASON_NUMBER = "season_number"
    private const val FIELD_NUMBER = "number"
    private const val FIELD_WATCHED_AT_UNKNOWN = "watched_at_unknown"
    private const val TYPE_EPISODE = "episode"
}
