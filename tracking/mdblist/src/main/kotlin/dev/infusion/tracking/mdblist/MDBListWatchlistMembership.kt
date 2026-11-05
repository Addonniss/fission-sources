package dev.infusion.tracking.mdblist

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

class MDBListWatchlistMembership(
    private val client: MDBListHttpClient,
    private val auth: MDBListAuthRepository,
) : WatchlistMembershipPort {

    override val providerId: String = MDBListAuthRepository.MDBLIST_PROVIDER_ID

    override fun capabilities(): MembershipCapabilities = MembershipCapabilities(
        add = true,
        remove = true,
        progressClear = true,
        writeKinds = setOf(WatchlistRemoteKind.MOVIE, WatchlistRemoteKind.SHOW),
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun read(request: WatchlistReadRequest): TrackingInboundOutcome<WatchlistRemotePage> {
        val query = buildMap {
            put("limit", request.limit.toString())
            request.cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) }
        }
        val outcome = send(
            MDBListRequest(
                method = MDBListMethod.GET,
                path = "/watchlist/items",
                query = query,
                readBudget = MDBListReadBudget.LOOKUP,
            ),
        )
        val body = when (outcome) {
            is MDBListOutcome.Success -> outcome.response.body
            is MDBListOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            is MDBListOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
        val records = ArrayList<WatchlistRemoteRecord>()
        var malformed = 0
        var bucketsFound = false
        for ((key, kind) in BUCKETS) {
            val array = root.optJSONArray(key) ?: continue
            bucketsFound = true
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    malformed++
                    continue
                }
                val record = parseItem(item, kind)
                if (record == null) malformed++ else records += record
            }
        }

        val items = if (bucketsFound) null else root.optJSONArray("items")
        if (items != null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index)
                if (item == null) {
                    malformed++
                    continue
                }
                val kind = kindOf(item.optString("mediatype", "")) ?: run { malformed++; continue }
                val record = parseItem(item, kind)
                if (record == null) malformed++ else records += record
            }
        }
        return TrackingInboundOutcome.Ok(
            WatchlistRemotePage(
                records = records,
                malformed = malformed,
                next = nextCursor(root),
            ),
        )
    }

    override suspend fun add(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (!isTitleLevel(ref)) return MembershipWriteResult.Unsupported(TITLE_LEVEL_ONLY)
        val body = writeBody(ref) ?: return MembershipWriteResult.Rejected(NO_WRITE_ALIAS)
        return outcomeToWrite(
            send(MDBListRequest(method = MDBListMethod.POST, path = ADD_PATH, json = body)),
        )
    }

    override suspend fun remove(ref: WatchlistMembershipRef): MembershipWriteResult {
        if (!isTitleLevel(ref)) return MembershipWriteResult.Unsupported(TITLE_LEVEL_ONLY)
        val body = writeBody(ref) ?: return MembershipWriteResult.Rejected(NO_WRITE_ALIAS)
        return outcomeToWrite(
            send(MDBListRequest(method = MDBListMethod.POST, path = REMOVE_PATH, json = body)),
        )
    }

    override suspend fun clearResumeProgress(ref: WatchlistMembershipRef): MembershipWriteResult {
        val ids = JSONObject().apply {
            ref.externalIds.tmdbId?.let { put("tmdb", it) }
            ref.externalIds.imdbId?.let { put("imdb", it) }
            ref.externalIds.tvdbId?.let { put("tvdb", it) }
        }
        if (ids.length() == 0) return MembershipWriteResult.Rejected(NO_WRITE_ALIAS)
        val target = if (ref.kind == WatchlistKind.MOVIE) "movie" else "show"
        val body = JSONObject()
            .put(target, JSONObject().put("ids", ids))
            .toString()
        return outcomeToWrite(
            send(MDBListRequest(method = MDBListMethod.POST, path = CLEAR_PATH, json = body)),
        )
    }

    private suspend fun send(request: MDBListRequest): MDBListOutcome {
        val token = readToken { auth.accessToken() } ?: return MDBListOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is MDBListOutcome.Unauthorized) return sanitize(outcome)
        val refreshed = readToken { auth.refresh() } ?: return sanitize(outcome)
        return sanitize(client.execute(request, refreshed))
    }

    private fun sanitize(outcome: MDBListOutcome): MDBListOutcome = when (outcome) {
        is MDBListOutcome.Success -> {
            val error = MDBListHttpClient.errorCode(outcome.response.body)
            if (error == null) outcome else MDBListOutcome.Rejected(error)
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

    private fun outcomeToWrite(outcome: MDBListOutcome): MembershipWriteResult = when (outcome) {
        is MDBListOutcome.Success -> MembershipWriteResult.Delivered
        is MDBListOutcome.Retry -> MembershipWriteResult.Retryable(outcome.reason)
        is MDBListOutcome.Rejected -> MembershipWriteResult.Rejected(outcome.reason)
        is MDBListOutcome.Unauthorized -> MembershipWriteResult.Unauthorized(outcome.reason)
    }

    private fun writeBody(ref: WatchlistMembershipRef): String? {
        val item = JSONObject().apply {
            ref.externalIds.tmdbId?.let { put("tmdb", it) }
            ref.externalIds.imdbId?.let { put("imdb", it) }
        }
        if (item.length() == 0) return null
        val array = JSONArray().put(item)
        val root = JSONObject()
        when (ref.kind) {
            WatchlistKind.MOVIE -> root.put("movies", array)
            WatchlistKind.SHOW -> root.put("shows", array)
        }
        return root.toString()
    }

    private fun isTitleLevel(ref: WatchlistMembershipRef): Boolean =
        ref.remoteKind == WatchlistRemoteKind.MOVIE || ref.remoteKind == WatchlistRemoteKind.SHOW

    private fun parseItem(item: JSONObject, kind: WatchlistRemoteKind): WatchlistRemoteRecord? {
        val ids = item.optJSONObject("ids")
        val external = TrackingExternalIds(
            tmdbId = ids?.optLongOrNull("tmdb") ?: item.optLongOrNull("tmdb_id") ?: item.optLongOrNull("id"),
            imdbId = ids?.optStringOrNull("imdb") ?: item.optStringOrNull("imdb_id"),
            tvdbId = ids?.optStringOrNull("tvdb")?.toLongOrNull() ?: item.optLongOrNull("tvdbid"),
        )
        val remoteRef = ids?.optStringOrNull("mdblist")
            ?: ids?.optLongOrNull("mdblist")?.toString()
            ?: ids?.optLongOrNull("tmdb")?.toString()
            ?: item.optLongOrNull("id")?.toString()
            ?: return null

        if (kind == WatchlistRemoteKind.MOVIE || kind == WatchlistRemoteKind.SHOW) {
            if (external.isEmpty) return null
        }
        return WatchlistRemoteRecord(
            remoteKind = kind,
            externalIds = external,
            remoteRef = remoteRef,
            title = item.optStringOrNull("title"),
            year = item.optIntOrNull("release_year") ?: item.optIntOrNull("year"),
            remoteAddedAt = instantOf(item.optStringOrNull("watchlist_at")),
        )
    }

    companion object {

        private val BUCKETS = listOf(
            "movies" to WatchlistRemoteKind.MOVIE,
            "shows" to WatchlistRemoteKind.SHOW,
            "seasons" to WatchlistRemoteKind.SEASON,
            "episodes" to WatchlistRemoteKind.EPISODE,
        )

        private const val ADD_PATH = "/watchlist/items/add"
        private const val REMOVE_PATH = "/watchlist/items/remove"
        private const val CLEAR_PATH = "/scrobble/clear"

        internal const val SHAPE_CHANGED = "mdblist_watchlist_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid MDBList authorization for this profile"
        internal const val TITLE_LEVEL_ONLY = "mdblist_watchlist_write_is_movies_and_shows_only"
        internal const val NO_WRITE_ALIAS = "mdblist_watchlist_write_requires_tmdb_or_imdb"

        internal fun nextCursor(root: JSONObject): String? =
            root.optStringOrNull("next_cursor")
                ?: root.optJSONObject("pagination")?.optStringOrNull("next_cursor")

        internal fun kindOf(mediatype: String): WatchlistRemoteKind? = when (mediatype.lowercase()) {
            "movie" -> WatchlistRemoteKind.MOVIE
            "show" -> WatchlistRemoteKind.SHOW
            "season" -> WatchlistRemoteKind.SEASON
            "episode" -> WatchlistRemoteKind.EPISODE
            else -> null
        }

        internal fun instantOf(value: String?): Instant? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
            return runCatching { Instant.ofEpochSecond(text.toLong()) }.getOrNull()
        }
    }
}
