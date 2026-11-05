package dev.infusion.tracking.trakt

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

class TraktWatchlistMembership(
    private val client: TraktHttpClient,
    private val auth: TraktAuthRepository,
) : WatchlistMembershipPort {

    override val providerId: String = TraktAuthRepository.TRAKT_PROVIDER_ID

    override fun capabilities(): MembershipCapabilities = MembershipCapabilities(
        add = true,
        remove = true,
        progressClear = true,
        writeKinds = setOf(WatchlistRemoteKind.MOVIE, WatchlistRemoteKind.SHOW),
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun read(request: WatchlistReadRequest): TrackingInboundOutcome<WatchlistRemotePage> {
        val page = if (request.cursor != null) request.cursor.toPageNumber() else request.page
        val limit = request.limit.coerceIn(1, TRAKT_MAX_LIMIT)
        val records = ArrayList<WatchlistRemoteRecord>()
        var malformed = 0
        var hasMore = false
        val incomplete = LinkedHashSet<WatchlistRemoteKind>()
        for ((type, remoteKind) in READ_TYPES) {
            val outcome = send(
                TraktRequest(
                    method = TraktMethod.GET,
                    path = "/sync/watchlist/$type/added/desc",
                    query = mapOf("page" to page.toString(), "limit" to limit.toString()),

                    readBudget = TraktReadBudget.LOOKUP,
                ),
            )
            when (outcome) {
                is TraktOutcome.Success -> {
                    val array = runCatching { JSONArray(outcome.response.body) }.getOrNull()
                        ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
                    for (index in 0 until array.length()) {
                        val entry = array.optJSONObject(index)
                        if (entry == null) {
                            malformed++
                            continue
                        }
                        val record = parseItem(entry, type)
                        if (record == null) malformed++ else records += record
                    }
                    if (hasMorePage(outcome.response.headers, page, array.length(), limit)) hasMore = true
                }
                is TraktOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)

                is TraktOutcome.Retry, is TraktOutcome.Rejected -> incomplete += remoteKind
            }
        }
        return TrackingInboundOutcome.Ok(
            WatchlistRemotePage(
                records = records,
                malformed = malformed,
                next = if (hasMore) "page:${page + 1}" else null,
                truncating = hasMore,
                incompleteRemoteKinds = incomplete,
            ),
        )
    }

    override suspend fun add(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (!isTitleLevel(ref)) return MembershipWriteResult.Unsupported(TITLE_LEVEL_ONLY)
        return write(TraktMethod.POST, ADD_PATH, writeBody(ref))
    }

    override suspend fun remove(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (!isTitleLevel(ref)) return MembershipWriteResult.Unsupported(TITLE_LEVEL_ONLY)
        return write(TraktMethod.POST, REMOVE_PATH, writeBody(ref))
    }

    override suspend fun clearResumeProgress(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (ref.remoteKind != WatchlistRemoteKind.MOVIE && ref.remoteKind != WatchlistRemoteKind.EPISODE) {
            return MembershipWriteResult.Unsupported(PROGRESS_CLEAR_NEEDS_UNIT)
        }
        return when (val found = findPlaybackId(ref)) {
            is PlaybackLookup.Absent -> MembershipWriteResult.Delivered
            is PlaybackLookup.Failed -> outcomeToWrite(found.outcome)
            is PlaybackLookup.Found -> write(
                method = TraktMethod.DELETE,
                path = "/sync/playback/${found.id}",
                json = null,
            )
        }
    }

    private suspend fun send(request: TraktRequest): TraktOutcome {
        val token = readToken { auth.accessToken() } ?: return TraktOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is TraktOutcome.Unauthorized) return outcome
        val refreshed = readToken { auth.refresh() } ?: return outcome
        return client.execute(request, refreshed)
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    private suspend fun write(method: TraktMethod, path: String, json: String?): MembershipWriteResult =
        outcomeToWrite(send(TraktRequest(method = method, path = path, json = json)))

    private fun outcomeToWrite(outcome: TraktOutcome): MembershipWriteResult = when (outcome) {
        is TraktOutcome.Success -> MembershipWriteResult.Delivered
        is TraktOutcome.Retry -> MembershipWriteResult.Retryable(outcome.reason)
        is TraktOutcome.Rejected -> MembershipWriteResult.Rejected(outcome.reason)
        is TraktOutcome.Unauthorized -> MembershipWriteResult.Unauthorized(outcome.reason)
    }

    private suspend fun findPlaybackId(ref: WatchlistMembershipRef): PlaybackLookup {
        val movies = ref.remoteKind == WatchlistRemoteKind.MOVIE
        val type = if (movies) "movies" else "episodes"
        for (page in 1..MAX_PLAYBACK_PAGES) {
            val outcome = send(
                TraktRequest(
                    method = TraktMethod.GET,
                    path = "/sync/playback/$type",
                    query = mapOf("page" to page.toString(), "limit" to PLAYBACK_PAGE_SIZE.toString()),
                ),
            )
            if (outcome !is TraktOutcome.Success) return PlaybackLookup.Failed(outcome)
            val array = runCatching { JSONArray(outcome.response.body) }.getOrNull()
                ?: return PlaybackLookup.Absent
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
                val number = episode.optIntOrNull("number")
                if (ref.seasonNumber == null || ref.episodeNumber == null) continue
                if (season != ref.seasonNumber || number != ref.episodeNumber) continue
                val showIds = item.optJSONObject("show")?.optJSONObject("ids")
                val matched = idsMatch(ref.externalIds, showIds) ||
                    idsMatch(ref.externalIds, episode.optJSONObject("ids"))
                if (matched) return PlaybackLookup.Found(id)
            }
            if (!hasMorePage(outcome.response.headers, page)) return PlaybackLookup.Absent
        }
        return PlaybackLookup.Absent
    }

    private fun idsMatch(ids: TrackingExternalIds, node: JSONObject?): Boolean {
        val stated = node ?: return false
        val tmdb = stated.optLongOrNull("tmdb")
        val imdb = stated.optStringOrNull("imdb")
        val tvdb = stated.optLongOrNull("tvdb")
        return (ids.tmdbId != null && ids.tmdbId == tmdb) ||
            (ids.imdbId != null && imdb != null && ids.imdbId.equals(imdb, ignoreCase = true)) ||
            (ids.tvdbId != null && ids.tvdbId == tvdb)
    }

    private fun writeBody(ref: WatchlistMembershipRef): String {
        val ids = JSONObject().apply {
            ref.externalIds.tmdbId?.let { put("tmdb", it) }
            ref.externalIds.imdbId?.let { put("imdb", it) }
            ref.externalIds.tvdbId?.let { put("tvdb", it) }
        }
        val item = JSONObject().put("ids", ids)
        val root = JSONObject()
        when (ref.kind) {
            WatchlistKind.MOVIE -> root.put("movies", JSONArray().put(item))
            WatchlistKind.SHOW -> root.put("shows", JSONArray().put(item))
        }
        return root.toString()
    }

    private fun isTitleLevel(ref: WatchlistMembershipRef): Boolean =
        ref.remoteKind == WatchlistRemoteKind.MOVIE || ref.remoteKind == WatchlistRemoteKind.SHOW

    private fun parseItem(entry: JSONObject, type: String): WatchlistRemoteRecord? {
        val listedAt = instantOf(entry.optStringOrNull("listed_at"))
        return when (type) {
            "movies" -> {
                val media = entry.optJSONObject("movie") ?: return null
                val external = media.optJSONObject("ids").toExternalIds()
                if (external.isEmpty) return null
                WatchlistRemoteRecord(
                    remoteKind = WatchlistRemoteKind.MOVIE,
                    externalIds = external,
                    remoteRef = media.optJSONObject("ids").ref() ?: return null,
                    title = media.optStringOrNull("title"),
                    year = media.optIntOrNull("year"),
                    remoteAddedAt = listedAt,
                )
            }
            "shows" -> {
                val media = entry.optJSONObject("show") ?: return null
                val external = media.optJSONObject("ids").toExternalIds()
                if (external.isEmpty) return null
                WatchlistRemoteRecord(
                    remoteKind = WatchlistRemoteKind.SHOW,
                    externalIds = external,
                    remoteRef = media.optJSONObject("ids").ref() ?: return null,
                    title = media.optStringOrNull("title"),
                    year = media.optIntOrNull("year"),
                    remoteAddedAt = listedAt,
                )
            }
            "seasons" -> {
                val season = entry.optJSONObject("season") ?: return null
                val show = entry.optJSONObject("show")
                val seasonIds = season.optJSONObject("ids")
                val external = seasonIds.toExternalIds()
                val series = show?.optJSONObject("ids").toExternalIds()
                if (external.isEmpty && series.isEmpty) return null
                WatchlistRemoteRecord(
                    remoteKind = WatchlistRemoteKind.SEASON,
                    externalIds = external,
                    remoteRef = seasonIds.ref() ?: return null,
                    title = show?.optStringOrNull("title"),
                    year = show?.optIntOrNull("year"),
                    seasonNumber = season.optIntOrNull("number"),
                    seriesExternalIds = series.takeUnless { it.isEmpty },
                    remoteAddedAt = listedAt,
                )
            }
            "episodes" -> {
                val episode = entry.optJSONObject("episode") ?: return null
                val show = entry.optJSONObject("show")
                val episodeIds = episode.optJSONObject("ids")
                val external = episodeIds.toExternalIds()
                val series = show?.optJSONObject("ids").toExternalIds()
                if (external.isEmpty && series.isEmpty) return null
                WatchlistRemoteRecord(
                    remoteKind = WatchlistRemoteKind.EPISODE,
                    externalIds = external,
                    remoteRef = episodeIds.ref() ?: return null,
                    title = show?.optStringOrNull("title"),
                    year = show?.optIntOrNull("year"),
                    seasonNumber = episode.optIntOrNull("season"),
                    episodeNumber = episode.optIntOrNull("number"),
                    seriesExternalIds = series.takeUnless { it.isEmpty },
                    remoteAddedAt = listedAt,
                )
            }
            else -> null
        }
    }

    companion object {

        private val READ_TYPES: List<Pair<String, WatchlistRemoteKind>> = listOf(
            "movies" to WatchlistRemoteKind.MOVIE,
            "shows" to WatchlistRemoteKind.SHOW,
            "seasons" to WatchlistRemoteKind.SEASON,
            "episodes" to WatchlistRemoteKind.EPISODE,
        )

        private const val TRAKT_MAX_LIMIT = 100

        private const val MAX_PLAYBACK_PAGES = 5
        private const val PLAYBACK_PAGE_SIZE = 100

        private const val ADD_PATH = "/sync/watchlist"
        private const val REMOVE_PATH = "/sync/watchlist/remove"

        internal const val SHAPE_CHANGED = "trakt_watchlist_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid Trakt authorization for this profile"
        internal const val TITLE_LEVEL_ONLY = "trakt_watchlist_write_is_title_level_only"
        internal const val PROGRESS_CLEAR_NEEDS_UNIT =
            "trakt_progress_clear_requires_a_movie_or_episode_unit"

        internal fun hasMorePage(
            headers: Map<String, String>,
            page: Int,
            returnedCount: Int = 0,
            limit: Int = 0,
        ): Boolean {
            val count = headers.entries
                .firstOrNull { it.key.equals("X-Pagination-Page-Count", ignoreCase = true) }
                ?.value?.substringBefore(',')?.trim()?.toIntOrNull()
            if (count != null) return page < count
            return limit > 0 && returnedCount >= limit
        }

        internal fun JSONObject?.ref(): String? =
            this?.optStringOrNull("trakt") ?: this?.optStringOrNull("slug")

        internal fun JSONObject?.toExternalIds(): TrackingExternalIds {
            if (this == null) return TrackingExternalIds()
            return TrackingExternalIds(
                tmdbId = optLongOrNull("tmdb"),
                imdbId = optStringOrNull("imdb"),
                tvdbId = optLongOrNull("tvdb"),
            )
        }

        internal fun instantOf(value: String?): Instant? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Instant.parse(text) }.getOrNull()
        }
    }

    private sealed interface PlaybackLookup {
        data class Found(val id: String) : PlaybackLookup
        data object Absent : PlaybackLookup
        data class Failed(val outcome: TraktOutcome) : PlaybackLookup
    }
}
