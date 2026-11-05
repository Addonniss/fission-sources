package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import org.json.JSONArray
import org.json.JSONObject

object TraktIdLookup {

    const val SEARCH_SEGMENT: String = "/search"

    enum class Authority(val wire: String) {
        IMDB("imdb"),
        TMDB("tmdb"),
        TVDB("tvdb"),
    }

    fun mediaType(kind: TrackingMediaKind): String = when (kind) {
        TrackingMediaKind.MOVIE -> "movie"
        TrackingMediaKind.SHOW -> "show"
    }

    fun ladderFor(kind: TrackingMediaKind): List<Authority> = when (kind) {
        TrackingMediaKind.MOVIE -> listOf(Authority.TMDB)
        TrackingMediaKind.SHOW -> listOf(Authority.TMDB, Authority.TVDB)
    }

    fun aliasFor(ids: TrackingExternalIds, authority: Authority): String? = when (authority) {
        Authority.IMDB -> ids.imdbId?.trim()?.takeIf { it.isNotEmpty() }
        Authority.TMDB -> ids.tmdbId?.takeIf { it > 0L }?.toString()
        Authority.TVDB -> ids.tvdbId?.takeIf { it > 0L }?.toString()
    }

    fun directPathId(ids: TrackingExternalIds): String? = aliasFor(ids, Authority.IMDB)

    fun request(authority: Authority, value: String, kind: TrackingMediaKind): TraktRequest = TraktRequest(
        method = TraktMethod.GET,
        path = "$SEARCH_SEGMENT/${authority.wire}/${encodePathSegment(value)}",
        query = mapOf("type" to mediaType(kind)),

        authenticated = true,
        retryable = true,
        readBudget = TraktReadBudget.LOOKUP,
    )

    fun resolve(
        body: String,
        authority: Authority,
        value: String,
        kind: TrackingMediaKind,
    ): TraktPathId = runCatching {
        val array = JSONArray(body)
        if (array.length() == 0) return TraktPathId.Unknown
        val wanted = mediaType(kind)
        var resolved: String? = null
        var verified = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: return TraktPathId.Undecidable
            if (row.optString("type", "").trim().lowercase() != wanted) continue
            val container = row.optJSONObject(wanted) ?: return TraktPathId.Undecidable
            val ids = container.optJSONObject("ids") ?: return TraktPathId.Undecidable
            if (!carries(ids, authority, value)) continue
            val pathId = pathIdOf(ids) ?: continue
            verified++
            resolved = pathId
        }
        when {
            verified == 1 -> TraktPathId.Resolved(resolved!!)

            else -> TraktPathId.Undecidable
        }
    }.getOrElse { TraktPathId.Undecidable }

    private fun carries(ids: JSONObject, authority: Authority, value: String): Boolean = when (authority) {
        Authority.IMDB -> ids.opt("imdb")?.let { scalar(it) } == value.trim()
        Authority.TMDB -> positiveLong(ids.opt("tmdb")) == positiveLong(value)
        Authority.TVDB -> positiveLong(ids.opt("tvdb")) == positiveLong(value)
    }

    private fun pathIdOf(ids: JSONObject): String? {
        positiveLong(ids.opt("trakt"))?.let { return it.toString() }
        ids.opt("slug")?.let { scalar(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        ids.opt("imdb")?.let { scalar(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value.trim(), "UTF-8")

    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim()
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    private fun positiveLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }
}

sealed interface TraktPathId {

    data class Resolved(val value: String) : TraktPathId

    data object Unknown : TraktPathId

    data object Undecidable : TraktPathId
}
