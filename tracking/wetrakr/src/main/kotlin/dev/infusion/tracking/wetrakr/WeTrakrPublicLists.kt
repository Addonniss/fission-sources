package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.publist.PublicListCapabilities
import dev.infusion.core.domain.publist.PublicListContentSource
import dev.infusion.core.domain.publist.PublicListDirectory
import dev.infusion.core.domain.publist.PublicListItem
import dev.infusion.core.domain.publist.PublicListPage
import dev.infusion.core.domain.publist.PublicListProvider
import dev.infusion.core.domain.publist.PublicListRef
import dev.infusion.core.domain.publist.PublicListResult
import dev.infusion.core.domain.publist.PublicListRoute
import dev.infusion.core.domain.publist.PublicListRouteSupport
import dev.infusion.core.domain.publist.PublicListSummary
import dev.infusion.core.domain.publist.PublicListUser
import dev.infusion.core.model.TitleKind
import kotlinx.coroutines.CancellationException
import org.json.JSONArray

class WeTrakrPublicLists(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,

    private val trace: ((String) -> Unit)? = null,
) : PublicListDirectory, PublicListContentSource {

    override val provider: PublicListProvider = PublicListProvider.WETRAKR

    override fun accountConnected(): Boolean = auth.isConnected()

    override fun capabilities(): PublicListCapabilities =
        if (!client.hasRegistration) {
            PublicListCapabilities(
                provider = provider,
                routes = emptyList(),
                notes = listOf(NOT_REGISTERED_NOTE),
            )
        } else {
            PublicListCapabilities(
                provider = provider,
                routes = listOf(
                    PublicListRouteSupport(PublicListRoute.TRENDING, TRENDING_LABEL),
                    PublicListRouteSupport(PublicListRoute.MY_LISTS, MY_LISTS_LABEL, requiresAccount = true),
                ),
                notes = NOTES,
            )
        }

    override suspend fun lists(
        route: PublicListRoute,
        query: String?,
    ): PublicListResult<List<PublicListSummary>> = when (route) {
        PublicListRoute.TRENDING -> trending()
        PublicListRoute.MY_LISTS -> myLists()
        else -> PublicListResult.Unsupported(notServed(route))
    }

    override suspend fun users(query: String): PublicListResult<List<PublicListUser>> =
        PublicListResult.Unsupported(NO_USER_SEARCH)

    override suspend fun userLists(username: String): PublicListResult<List<PublicListSummary>> =
        PublicListResult.Unsupported(NO_USER_LOOKUP)

    override suspend fun page(ref: PublicListRef, cursor: String?): PublicListResult<PublicListPage> {
        if (ref.provider != provider) return PublicListResult.Failed(FOREIGN_PROVIDER)
        val listId = ref.listId.trim()
        if (!SAFE_LIST_ID.matches(listId)) return PublicListResult.Failed(UNSUPPORTED_LIST_ID)
        val pageNumber = pageNumberOf(cursor) ?: return PublicListResult.Failed(UNKNOWN_CURSOR)
        val outcome = send(
            WeTrakrRequest(
                method = WeTrakrMethod.GET,
                path = "${WeTrakrHttpClient.LISTS_PATH}/$listId/items/$ITEMS_TARGET",
                query = mapOf("page" to pageNumber.toString(), "limit" to PAGE_SIZE.toString()),

                optionalAuth = true,
                limits = WeTrakrHttpClient.READ_LIMITS,
            ),
        )
        return when (outcome) {
            is WeTrakrOutcome.Success -> {
                val array = runCatching { JSONArray(outcome.response.body) }.getOrNull()
                    ?: return failure(SHAPE_CHANGED, "items")
                val items = parseItems(array)

                if (items.isEmpty() && array.length() > 0) return failure(SHAPE_CHANGED, "items")
                val current = intHeader(outcome.response.headers, PAGE_HEADER) ?: pageNumber
                val pageCount = intHeader(outcome.response.headers, PAGE_COUNT_HEADER)
                val hasMore = pageCount != null && pageCount > 0 && current < pageCount
                trace?.invoke(
                    "wetrakr lists_items page=$current rows=${items.size} more=$hasMore " +
                        "pages=${pageCount ?: "NONE"}",
                )
                PublicListResult.Success(
                    PublicListPage(
                        items = items,
                        hasMore = hasMore,
                        nextCursor = if (hasMore) "$PAGE_CURSOR_PREFIX${current + 1}" else null,
                    ),
                )
            }
            is WeTrakrOutcome.Unauthorized -> failure(UNAUTHORIZED, "items")
            is WeTrakrOutcome.Retry -> failure(outcome.reason, "items")
            is WeTrakrOutcome.Rejected -> failure(outcome.reason, "items")
        }
    }

    private suspend fun trending(): PublicListResult<List<PublicListSummary>> =
        discover(
            route = PublicListRoute.TRENDING,
            request = {
                WeTrakrRequest(
                    method = WeTrakrMethod.GET,
                    path = WeTrakrHttpClient.LISTS_TRENDING_PATH,
                    query = mapOf("limit" to DISCOVERY_LIMIT.toString()),
                    optionalAuth = true,
                    limits = WeTrakrHttpClient.READ_LIMITS,
                )
            },
        )

    private suspend fun myLists(): PublicListResult<List<PublicListSummary>> {
        if (!auth.isConnected()) {
            trace?.invoke("wetrakr lists_dir route=MY_LISTS failed=$UNAUTHORIZED")
            return PublicListResult.Failed(UNAUTHORIZED)
        }
        return discover(
            route = PublicListRoute.MY_LISTS,
            request = {
                WeTrakrRequest(
                    method = WeTrakrMethod.GET,
                    path = WeTrakrHttpClient.ACCOUNT_LISTS_PATH,
                    authenticated = true,
                    limits = WeTrakrHttpClient.READ_LIMITS,
                )
            },
        )
    }

    private suspend fun discover(
        route: PublicListRoute,
        request: () -> WeTrakrRequest,
    ): PublicListResult<List<PublicListSummary>> = when (val outcome = send(request())) {
        is WeTrakrOutcome.Success -> {
            val array = runCatching { JSONArray(outcome.response.body) }.getOrNull()
                ?: return failure(SHAPE_CHANGED, "dir route=$route")
            val summaries = parseSummaries(array)
            if (summaries.isEmpty() && array.length() > 0) {
                return failure(SHAPE_CHANGED, "dir route=$route")
            }
            trace?.invoke("wetrakr lists_dir route=$route rows=${summaries.size}")
            PublicListResult.Success(summaries)
        }
        is WeTrakrOutcome.Unauthorized -> failure(UNAUTHORIZED, "dir route=$route")
        is WeTrakrOutcome.Retry -> failure(outcome.reason, "dir route=$route")
        is WeTrakrOutcome.Rejected -> failure(outcome.reason, "dir route=$route")
    }

    private fun failure(reason: String, what: String): PublicListResult.Failed {
        trace?.invoke("wetrakr lists_$what failed=$reason")
        return PublicListResult.Failed(reason)
    }

    private suspend fun send(request: WeTrakrRequest): WeTrakrOutcome {
        val token = readToken { auth.accessToken() }
        if (request.authenticated && token == null) {
            return WeTrakrOutcome.Unauthorized(NO_AUTHORIZATION)
        }
        val outcome = client.execute(request, token)
        if (outcome !is WeTrakrOutcome.Unauthorized) return outcome
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

    private fun intHeader(headers: Map<String, String>, name: String): Int? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.trim()?.toIntOrNull()

    companion object {

        const val DISCOVERY_LIMIT: Int = 20

        const val PAGE_SIZE: Int = 50

        internal const val ITEMS_TARGET: String = "all"

        internal const val PAGE_HEADER = "X-Pagination-Page"
        internal const val PAGE_COUNT_HEADER = "X-Pagination-Page-Count"

        internal const val PAGE_CURSOR_PREFIX = "page:"

        internal const val SHAPE_CHANGED = "wetrakr_response_shape_changed"
        internal const val UNAUTHORIZED = "unauthorized"
        internal const val NO_AUTHORIZATION = "no stored WeTrakr authorization"
        internal const val FOREIGN_PROVIDER = "not_a_wetrakr_list"
        internal const val UNSUPPORTED_LIST_ID = "unsupported_wetrakr_list_id"
        internal const val UNKNOWN_CURSOR = "unknown_cursor"
        internal const val NO_USER_SEARCH = "wetrakr publishes no user search"
        internal const val NO_USER_LOOKUP = "wetrakr does not expose a public user-list lookup in this build"

        internal const val TRENDING_LABEL = "Trending Lists"
        internal const val MY_LISTS_LABEL = "My Lists"

        internal const val NOT_REGISTERED_NOTE =
            "WeTrakr is not registered in this build, so no WeTrakr list route is available."

        internal val NOTES: List<String> = listOf(
            "Trending Lists is the provider's own public ranking and needs no account; My Lists needs the connected WeTrakr account.",
            "WeTrakr publishes no list search and no user search, so neither route is offered.",
            "A list its own plan has locked reports the provider's refusal rather than an empty list.",
        )

        internal val SAFE_LIST_ID = Regex("[A-Za-z0-9_-]{1,64}")

        internal fun pageNumberOf(cursor: String?): Int? {
            if (cursor == null) return 1
            val trimmed = cursor.trim()
            if (!trimmed.startsWith(PAGE_CURSOR_PREFIX)) return null
            val number = trimmed.removePrefix(PAGE_CURSOR_PREFIX).toIntOrNull() ?: return null
            return number.takeIf { it >= 1 }
        }

        internal fun notServed(route: PublicListRoute): String = when (route) {
            PublicListRoute.LIKED_LISTS ->
                "wetrakr liked lists are outside this build's WeTrakr surface"
            PublicListRoute.USER_LISTS -> NO_USER_LOOKUP
            PublicListRoute.SEARCH_LISTS, PublicListRoute.SEARCH_USERS -> NO_USER_SEARCH
            PublicListRoute.POPULAR, PublicListRoute.CURATED, PublicListRoute.OFFICIAL ->
                "wetrakr serves no such public list category"
            PublicListRoute.TRENDING, PublicListRoute.MY_LISTS -> "not requested"
        }

        internal fun parseSummaries(array: JSONArray): List<PublicListSummary> {
            val out = ArrayList<PublicListSummary>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val listId = idText(entry.opt("id")) ?: continue
                val title = textOf(entry, "name") ?: continue
                val owner = textOf(entry.optJSONObject("owner")?.optJSONObject("info"), "username")
                    ?: textOf(entry, "owner_username")
                out += PublicListSummary(
                    ref = PublicListRef(
                        provider = PublicListProvider.WETRAKR,
                        listId = listId,
                        owner = owner,
                    ),
                    title = title,
                    owner = owner,
                    itemCount = countOf(entry, "items_count") ?: countOf(entry, "total_items"),
                )
            }
            return out
        }

        internal fun parseItems(array: JSONArray): List<PublicListItem> {
            val out = ArrayList<PublicListItem>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val title = textOf(entry, "title") ?: textOf(entry, "name") ?: continue
                val ids = entry.optJSONObject("ids")
                out += PublicListItem(
                    providerItemId = textOf(entry, "list_item_id")
                        ?: idText(entry.opt("id"))
                        ?: "$index",
                    title = title,
                    year = yearOf(entry),
                    kind = kindOf(textOf(entry, "type")),
                    tmdbId = numberText(ids, "tmdb")?.toLongOrNull(),
                    imdbId = textOf(ids, "imdb"),
                    tvdbId = numberText(ids, "tvdb")?.toLongOrNull(),
                    rank = countOf(entry, "list_item_rank"),
                )
            }
            return out
        }

        internal fun kindOf(type: String?): TitleKind? = when (type?.lowercase()) {
            "movie" -> TitleKind.MOVIE
            "show", "tv" -> TitleKind.SERIES
            else -> null
        }

        internal fun yearOf(entry: org.json.JSONObject): Int? =
            listOf("release_date", "first_air_date", "air_date", "year")
                .firstNotNullOfOrNull { key -> textOf(entry, key)?.take(4)?.toIntOrNull() }

        internal fun idText(value: Any?): String? = when (value) {
            null, org.json.JSONObject.NULL -> null
            is Int -> value.toString()
            is Long -> value.toString()
            is Number -> {
                val asLong = value.toLong()
                if (value.toDouble() == asLong.toDouble()) asLong.toString() else value.toString()
            }
            else -> value.toString().trim().takeIf { it.isNotEmpty() }
        }

        private fun numberText(obj: org.json.JSONObject?, key: String): String? {
            if (obj == null || !obj.has(key) || obj.isNull(key)) return null
            return idText(obj.opt(key))
        }

        private fun textOf(obj: org.json.JSONObject?, key: String): String? {
            if (obj == null || !obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key, "").trim().takeIf { it.isNotEmpty() }
        }

        private fun countOf(obj: org.json.JSONObject, key: String): Int? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optInt(key, 0)
        }
    }
}
