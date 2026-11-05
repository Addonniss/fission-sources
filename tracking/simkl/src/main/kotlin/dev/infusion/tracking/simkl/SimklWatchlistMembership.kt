package dev.infusion.tracking.simkl

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.watchlist.MembershipCapabilities
import dev.infusion.core.domain.watchlist.MembershipWriteResult
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipPort
import dev.infusion.core.domain.watchlist.WatchlistMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistReadRequest
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.domain.watchlist.WatchlistRemotePage
import dev.infusion.core.domain.watchlist.WatchlistRemoteRecord
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class SimklWatchlistMembership(
    private val client: SimklHttpClient,
    private val auth: SimklAuthRepository,
) : WatchlistMembershipPort {

    override val providerId: String = SimklAuthRepository.SIMKL_PROVIDER_ID

    override fun capabilities(): MembershipCapabilities = MembershipCapabilities(
        add = true,
        remove = false,
        progressClear = true,
        writeKinds = setOf(WatchlistRemoteKind.MOVIE, WatchlistRemoteKind.SHOW),
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun read(request: WatchlistReadRequest): TrackingInboundOutcome<WatchlistRemotePage> {
        val records = ArrayList<WatchlistRemoteRecord>()
        var malformed = 0
        for (type in READ_TYPES) {
            val outcome = send(
                SimklRequest(
                    method = SimklMethod.GET,
                    path = "/sync/all-items/$type/${STATUS}",
                    readBudget = SimklReadBudget.LOOKUP,
                ),
            )
            val body = when (outcome) {
                is SimklOutcome.Success -> outcome.response.body
                is SimklOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
                is SimklOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is SimklOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
            }
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)

            val array = root.optJSONArray(type) ?: continue
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    malformed++
                    continue
                }
                val record = parseItem(item, type)
                if (record == null) malformed++ else records += record
            }
        }
        return TrackingInboundOutcome.Ok(WatchlistRemotePage(records = records, malformed = malformed))
    }

    override suspend fun add(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (!isTitleLevel(ref)) return MembershipWriteResult.Unsupported(TITLE_LEVEL_ONLY)
        val ids = JSONObject().apply {
            ref.externalIds.tmdbId?.let { put("tmdb", it) }
            ref.externalIds.imdbId?.let { put("imdb", it) }
            ref.externalIds.tvdbId?.let { put("tvdb", it) }
        }
        if (ids.length() == 0) return MembershipWriteResult.Rejected(NO_WRITE_ALIAS)
        val item = JSONObject().put("ids", ids).put("to", STATUS)
        val array = JSONArray().put(item)
        val root = JSONObject()
        when (ref.kind) {
            WatchlistKind.MOVIE -> root.put("movies", array)
            WatchlistKind.SHOW -> root.put("shows", array)
        }
        return outcomeToWrite(
            send(SimklRequest(method = SimklMethod.POST, path = ADD_PATH, json = root.toString())),
        )
    }

    override suspend fun remove(ref: WatchlistMembershipRef): MembershipWriteResult =
        MembershipWriteResult.Unsupported(SIMKL_NO_SAFE_REMOVAL)

    override suspend fun clearResumeProgress(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (ref.remoteKind != WatchlistRemoteKind.MOVIE && ref.remoteKind != WatchlistRemoteKind.EPISODE) {
            return MembershipWriteResult.Unsupported(PROGRESS_CLEAR_NEEDS_UNIT)
        }
        return when (val found = findPlaybackId(ref)) {
            is PlaybackLookup.Absent -> MembershipWriteResult.Delivered
            is PlaybackLookup.Failed -> outcomeToWrite(found.outcome)
            is PlaybackLookup.Found -> outcomeToWrite(
                send(SimklRequest(method = SimklMethod.DELETE, path = "/sync/playback/${found.id}")),
            )
        }
    }

    private suspend fun send(request: SimklRequest): SimklOutcome {
        val token = readToken { auth.accessToken() } ?: return SimklOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is SimklOutcome.Unauthorized) return sanitize(outcome)
        val refreshed = readToken { auth.refresh() } ?: return sanitize(outcome)
        return sanitize(client.execute(request, refreshed))
    }

    private fun sanitize(outcome: SimklOutcome): SimklOutcome = when (outcome) {
        is SimklOutcome.Success -> {
            val error = SimklHttpClient.errorCode(outcome.response.body)
            if (error == null) outcome else SimklOutcome.Rejected(error)
        }
        else -> outcome
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    private fun outcomeToWrite(outcome: SimklOutcome): MembershipWriteResult = when (outcome) {
        is SimklOutcome.Success -> MembershipWriteResult.Delivered
        is SimklOutcome.Retry -> MembershipWriteResult.Retryable(outcome.reason)
        is SimklOutcome.Rejected -> MembershipWriteResult.Rejected(outcome.reason)
        is SimklOutcome.Unauthorized -> MembershipWriteResult.Unauthorized(outcome.reason)
    }

    private suspend fun findPlaybackId(ref: WatchlistMembershipRef): PlaybackLookup {
        val outcome = send(SimklRequest(method = SimklMethod.GET, path = "/sync/playback"))
        if (outcome !is SimklOutcome.Success) return PlaybackLookup.Failed(outcome)
        val body = outcome.response.body
        val array = runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body) }.getOrNull()?.optJSONArray("playback")
            ?: return PlaybackLookup.Absent
        val movies = ref.remoteKind == WatchlistRemoteKind.MOVIE
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (movies) {
                val movie = item.optJSONObject("movie") ?: continue
                if (idsMatch(ref.externalIds, movie.optJSONObject("ids"))) return PlaybackLookup.Found(id)
                continue
            }
            val episode = item.optJSONObject("episode") ?: continue
            val season = episode.optIntOrNull("season")
            val number = episode.optIntOrNull("episode") ?: episode.optIntOrNull("number")
            if (ref.seasonNumber == null || ref.episodeNumber == null) continue
            if (season != ref.seasonNumber || number != ref.episodeNumber) continue
            val showIds = item.optJSONObject("show")?.optJSONObject("ids")
            val matched = idsMatch(ref.externalIds, showIds) ||
                idsMatch(ref.externalIds, episode.optJSONObject("ids"))
            if (matched) return PlaybackLookup.Found(id)
        }
        return PlaybackLookup.Absent
    }

    private fun idsMatch(ids: TrackingExternalIds, node: JSONObject?): Boolean {
        val stated = node ?: return false
        return (ids.tmdbId != null && ids.tmdbId == stated.longId("tmdb")) ||
            (ids.imdbId != null && ids.imdbId.equals(stated.strId("imdb"), ignoreCase = true)) ||
            (ids.tvdbId != null && ids.tvdbId == stated.longId("tvdb"))
    }

    private fun isTitleLevel(ref: WatchlistMembershipRef): Boolean =
        ref.remoteKind == WatchlistRemoteKind.MOVIE || ref.remoteKind == WatchlistRemoteKind.SHOW

    private fun parseItem(item: JSONObject, type: String): WatchlistRemoteRecord? {
        val nestedKey = when (type) {
            "movies" -> "movie"
            "shows" -> "show"
            else -> "anime"
        }
        val nested = item.optJSONObject(nestedKey) ?: item
        val ids = nested.optJSONObject("ids") ?: item.optJSONObject("ids")
        val external = TrackingExternalIds(
            tmdbId = ids?.longId("tmdb"),
            imdbId = ids?.strId("imdb"),
            tvdbId = ids?.longId("tvdb"),
        )
        if (external.isEmpty) return null
        val remoteRef = ids?.strId("simkl_id") ?: ids?.strId("simkl") ?: ids?.strId("slug") ?: return null

        return WatchlistRemoteRecord(
            remoteKind = kindOf(type, nested),
            externalIds = external,
            remoteRef = remoteRef,
            title = nested.optStringOrNull("title"),
            year = nested.optIntOrNull("year"),
            remoteAddedAt = instantOf(item.optStringOrNull("added_to_watchlist_at")),
        )
    }

    private fun instantOf(value: String?): Instant? =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun kindOf(type: String, nested: JSONObject): WatchlistRemoteKind = when (type) {
        "movies" -> WatchlistRemoteKind.MOVIE
        "shows" -> WatchlistRemoteKind.SHOW
        else -> if (nested.optString("anime_type", "").equals("movie", ignoreCase = true)) {
            WatchlistRemoteKind.MOVIE
        } else {
            WatchlistRemoteKind.SHOW
        }
    }

    companion object {

        private val READ_TYPES = listOf("movies", "shows", "anime")

        private const val STATUS = "plantowatch"

        private const val ADD_PATH = "/sync/add-to-list"

        internal const val SHAPE_CHANGED = "simkl_watchlist_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid Simkl authorization for this profile"
        internal const val TITLE_LEVEL_ONLY = "simkl_watchlist_write_is_title_level_only"
        internal const val NO_WRITE_ALIAS = "simkl_watchlist_write_requires_a_stable_alias"
        internal const val PROGRESS_CLEAR_NEEDS_UNIT =
            "simkl_progress_clear_requires_a_movie_or_episode_unit"

        internal const val SIMKL_NO_SAFE_REMOVAL = "simkl_no_safe_plan_to_watch_removal"
    }

    private sealed interface PlaybackLookup {
        data class Found(val id: String) : PlaybackLookup
        data object Absent : PlaybackLookup
        data class Failed(val outcome: SimklOutcome) : PlaybackLookup
    }
}

private fun JSONObject.longId(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong().takeIf { it != 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it != 0L }
        else -> null
    }
}

private fun JSONObject.strId(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().takeIf { it.isNotEmpty() }
}
