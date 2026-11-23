package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteRecord
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

object WeTrakrWatchlistMapping {

    const val STATUS_PLANNING: String = "planning"

    const val TARGET_MOVIES: String = "movies"
    const val TARGET_SHOWS: String = "shows"

    val READ_TARGETS: List<String> = listOf(TARGET_MOVIES, TARGET_SHOWS)

    private const val TYPE_MOVIE = "movie"
    private const val TYPE_SHOW = "show"

    fun planningListRequest(target: String, page: Int, limit: Int): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = "${WeTrakrHttpClient.TRACKING_PATH}/$STATUS_PLANNING/$target",
        query = mapOf("page" to page.toString(), "limit" to limit.toString()),
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    fun addRequest(ref: WatchlistMembershipRef): WeTrakrRequest? = writeRequest(
        path = WeTrakrHttpClient.TRACKING_WRITE_PATH,
        ref = ref,
    )

    fun removeRequest(ref: WatchlistMembershipRef): WeTrakrRequest? = writeRequest(
        path = WeTrakrHttpClient.TRACKING_REMOVE_PATH,
        ref = ref,
    )

    private fun writeRequest(path: String, ref: WatchlistMembershipRef): WeTrakrRequest? {
        val body = writeBody(ref) ?: return null
        return WeTrakrRequest(
            method = WeTrakrMethod.POST,
            path = path,
            json = body,
            authenticated = true,
        )
    }

    fun writeBody(ref: WatchlistMembershipRef): String? {
        val ids = provenIds(ref.externalIds)
        if (ids.length() == 0) return null
        val item = JSONObject().put("ids", ids).put("status", STATUS_PLANNING)
        val items = JSONArray().put(item)
        val root = JSONObject()
        when (ref.kind) {
            WatchlistKind.MOVIE -> root.put(TARGET_MOVIES, items)
            WatchlistKind.SHOW -> root.put(TARGET_SHOWS, items)
        }
        return root.toString()
    }

    internal fun provenIds(ids: TrackingExternalIds): JSONObject = JSONObject().apply {
        ids.tmdbId?.takeIf { it > 0 }?.let { put("tmdb", it) }
        ids.imdbId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("imdb", it) }
        ids.tvdbId?.takeIf { it > 0 }?.let { put("tvdb", it) }
    }

    fun records(body: String, target: String): Parsed? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val expectedType = if (target == TARGET_SHOWS) TYPE_SHOW else TYPE_MOVIE
        val expectedKind = if (target == TARGET_SHOWS) WatchlistRemoteKind.SHOW else WatchlistRemoteKind.MOVIE
        val records = ArrayList<WatchlistRemoteRecord>(array.length())
        var malformed = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            val record = row?.let { recordOf(it, expectedType, expectedKind) }
            if (record == null) malformed++ else records += record
        }
        return Parsed(records = records, malformed = malformed)
    }

    data class Parsed(val records: List<WatchlistRemoteRecord>, val malformed: Int)

    private fun recordOf(
        row: JSONObject,
        expectedType: String,
        expectedKind: WatchlistRemoteKind,
    ): WatchlistRemoteRecord? {
        val statedType = row.optStringOrNull("type")?.trim()?.lowercase()
        if (statedType != null && statedType != expectedType) return null
        val statedStatus = row.optStringOrNull("status")?.trim()?.lowercase()
        if (statedStatus != null && statedStatus != STATUS_PLANNING) return null
        val external = WeTrakrInboundMapping.externalIds(row.optJSONObject("ids"))
        if (external.isEmpty) return null
        val remoteRef = (row.optLongOrNull("id") ?: row.optStringOrNull("id")?.toLongOrNull())
            ?.toString()
            ?: return null
        return WatchlistRemoteRecord(
            remoteKind = expectedKind,
            externalIds = external,
            remoteRef = remoteRef,
            title = row.optStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() },
            year = yearOf(row),
            remoteAddedAt = instantOf(row.optStringOrNull("tracked_at")),
        )
    }

    private fun instantOf(value: String?): Instant? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun yearOf(row: JSONObject): Int? {
        row.optIntOrNull("year")?.takeIf { it > 0 }?.let { return it }
        val raw = row.optStringOrNull("release_date")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Instant.parse(raw).atOffset(ZoneOffset.UTC).year }.getOrNull()?.let { return it }
        return runCatching { java.time.LocalDate.parse(raw).year }.getOrNull()
    }

    data class WriteSummary(
        val added: Int,
        val existing: Int,
        val removed: Int,
        val notFound: Int,
        val errored: Int,
        val errorCodes: List<String>,
    ) {

        val classifiedNothing: Boolean
            get() = added == 0 && existing == 0 && removed == 0 && notFound == 0 && errored == 0
    }

    fun writeSummary(body: String): WriteSummary? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val errored = root.optJSONObject("errored")
        return WriteSummary(
            added = countIn(root.optJSONObject("added")),
            existing = countIn(root.optJSONObject("existing")),
            removed = countIn(root.optJSONObject("removed")),
            notFound = itemsIn(root.optJSONObject("notFound")),
            errored = itemsIn(errored),
            errorCodes = errorCodes(errored),
        )
    }

    private fun countIn(summary: JSONObject?): Int {
        if (summary == null) return 0
        val planning = summary.optJSONObject(STATUS_PLANNING)
        if (planning != null) {
            var total = 0
            for (key in planning.keys()) {
                total += planning.optInt(key, 0).coerceAtLeast(0)
            }
            return total
        }
        return summary.optInt("total", 0).coerceAtLeast(0)
    }

    private fun itemsIn(section: JSONObject?): Int {
        if (section == null) return 0
        var total = 0
        for (key in section.keys()) {
            total += section.optJSONArray(key)?.length() ?: 0
        }
        return total
    }

    private fun errorCodes(section: JSONObject?): List<String> {
        if (section == null) return emptyList()
        val codes = LinkedHashSet<String>()
        for (key in section.keys()) {
            val array = section.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val code = array.optJSONObject(index)
                    ?.optStringOrNull("error")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                codes += code
            }
        }
        return codes.toList()
    }

    fun hasMorePage(pageCount: Int?, page: Int, returnedCount: Int, limit: Int): Boolean {
        if (pageCount != null) return page < pageCount
        return limit > 0 && returnedCount >= limit
    }
}
