package dev.infusion.tracking.trakt

import dev.infusion.core.domain.publist.PublicListCapabilities
import dev.infusion.core.domain.publist.PublicListContentSource
import dev.infusion.core.domain.publist.PublicListDirectory
import dev.infusion.core.domain.publist.PublicListDirectoryPage
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
import org.json.JSONArray
import org.json.JSONObject

class TraktPublicLists(
    private val client: TraktHttpClient,
    private val pageSize: Int = DISCOVERY_PAGE_SIZE,

    private val accountToken: suspend () -> String? = { null },

    private val isAccountConnected: () -> Boolean = { false },

    private val refreshAccountToken: suspend () -> String? = { null },
) : PublicListDirectory, PublicListContentSource {

    override val provider: PublicListProvider = PublicListProvider.TRAKT

    override fun accountConnected(): Boolean = isAccountConnected()

    override fun capabilities(): PublicListCapabilities = PublicListCapabilities(
        provider = provider,
        routes = listOf(
            PublicListRouteSupport(PublicListRoute.POPULAR, "Popular Lists"),
            PublicListRouteSupport(PublicListRoute.TRENDING, "Trending Lists"),
            PublicListRouteSupport(
                PublicListRoute.SEARCH_LISTS,
                "Search Lists",
                inputLabel = "Search",
            ),
            PublicListRouteSupport(
                PublicListRoute.SEARCH_USERS,
                "Search Users",
                requiresAccount = true,
                inputLabel = "Search",
            ),
            PublicListRouteSupport(
                PublicListRoute.USER_LISTS,
                "User's Public Lists",
                inputLabel = "Username",
            ),
            PublicListRouteSupport(PublicListRoute.MY_LISTS, "My Lists", requiresAccount = true),
            PublicListRouteSupport(PublicListRoute.LIKED_LISTS, "Liked Lists", requiresAccount = true),
        ),
        notes = listOf(
            "User search needs the connected Trakt account; an EXACT username lookup is public.",
        ),
    )

    override suspend fun lists(
        route: PublicListRoute,
        query: String?,
    ): PublicListResult<List<PublicListSummary>> =
        when (val page = listPage(route, query, cursor = null)) {
            is PublicListResult.Success -> PublicListResult.Success(page.value.lists)
            is PublicListResult.Unsupported -> page
            is PublicListResult.Failed -> page
        }

    override suspend fun listPage(
        route: PublicListRoute,
        query: String?,
        cursor: String?,
    ): PublicListResult<PublicListDirectoryPage> {
        val pageNumber = cursor.toPageNumber()
        return when (route) {
            PublicListRoute.POPULAR -> fetchPage("/lists/popular", ::traktListEntry, pageNumber = pageNumber)
            PublicListRoute.TRENDING -> fetchPage("/lists/trending", ::traktListEntry, pageNumber = pageNumber)
            PublicListRoute.SEARCH_LISTS -> {
                val term = query?.trim().orEmpty()
                if (term.isEmpty()) {

                    PublicListResult.Success(PublicListDirectoryPage(emptyList()))
                } else {
                    fetchPage(
                        "/search/list",
                        ::traktListEntry,
                        query = mapOf("query" to term),
                        pageNumber = pageNumber,
                    )
                }
            }

            PublicListRoute.MY_LISTS -> accountPage("/users/me/lists", ::traktListObject, pageNumber = pageNumber)
            PublicListRoute.LIKED_LISTS -> accountPage("/users/me/likes/lists", ::traktListEntry, pageNumber = pageNumber)
            PublicListRoute.USER_LISTS -> {
                val handle = query?.trim()?.trimStart('@').orEmpty()
                if (handle.isEmpty()) {
                    PublicListResult.Success(PublicListDirectoryPage(emptyList()))
                } else {
                    fetchPage("/users/$handle/lists", ::traktListObject, pageNumber = pageNumber)
                }
            }

            PublicListRoute.SEARCH_USERS -> PublicListResult.Unsupported("search users through the user route")

            PublicListRoute.CURATED,
            PublicListRoute.OFFICIAL,
            -> PublicListResult.Unsupported("trakt has no such list category")
        }
    }

    override suspend fun users(query: String): PublicListResult<List<PublicListUser>> {
        val term = query.trim()
        if (term.isEmpty()) return PublicListResult.Success(emptyList())
        return when (
            val outcome = accountResponse("/search/user", query = mapOf("query" to term))
        ) {
            is PublicListResult.Success -> {
                val array = runCatching { JSONArray(outcome.value.body) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                val users = parseUsers(array)

                if (array.length() > 0 && users.isEmpty()) {
                    PublicListResult.Failed(SHAPE_CHANGED)
                } else {
                    PublicListResult.Success(users)
                }
            }
            is PublicListResult.Failed -> outcome
            is PublicListResult.Unsupported -> outcome
        }
    }

    override suspend fun userLists(username: String): PublicListResult<List<PublicListSummary>> =
        when (val page = listPage(PublicListRoute.USER_LISTS, query = username, cursor = null)) {
            is PublicListResult.Success -> PublicListResult.Success(page.value.lists)
            is PublicListResult.Unsupported -> page
            is PublicListResult.Failed -> page
        }

    override suspend fun page(ref: PublicListRef, cursor: String?): PublicListResult<PublicListPage> {
        val pageNumber = cursor.toPageNumber()
        val path = "/lists/${ref.listId}/items"
        return when (
            val outcome = client.execute(
                TraktRequest(
                    method = TraktMethod.GET,
                    path = path,
                    query = mapOf("page" to pageNumber.toString(), "limit" to pageSize.toString()),
                    authenticated = false,

                    readBudget = TraktReadBudget.LOOKUP,
                ),
            )
        ) {
            is TraktOutcome.Success -> {
                val array = runCatching { JSONArray(outcome.response.body) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                PublicListPage(
                    items = parseItems(array),
                    hasMore = hasMorePage(outcome.response.headers),
                    nextCursor = nextCursor(outcome.response.headers),
                ).let { PublicListResult.Success(it) }
            }
            is TraktOutcome.Unauthorized -> PublicListResult.Failed(UNAUTHORIZED)
            is TraktOutcome.Retry -> PublicListResult.Failed(outcome.reason)
            is TraktOutcome.Rejected -> PublicListResult.Failed(outcome.reason)
        }
    }

    private suspend fun fetchPage(
        path: String,
        parse: (JSONObject) -> PublicListSummary?,
        query: Map<String, String> = emptyMap(),
        pageNumber: Int = 1,
    ): PublicListResult<PublicListDirectoryPage> = when (
        val outcome = client.execute(
            TraktRequest(
                method = TraktMethod.GET,
                path = path,
                query = query + mapOf("limit" to pageSize.toString(), "page" to pageNumber.toString()),
                authenticated = false,
                readBudget = TraktReadBudget.LOOKUP,
            ),
        )
    ) {
        is TraktOutcome.Success -> directoryPage(outcome.response, parse)
        is TraktOutcome.Unauthorized -> PublicListResult.Failed(UNAUTHORIZED)
        is TraktOutcome.Retry -> PublicListResult.Failed(outcome.reason)
        is TraktOutcome.Rejected -> PublicListResult.Failed(outcome.reason)
    }

    private suspend fun accountPage(
        path: String,
        parse: (JSONObject) -> PublicListSummary?,
        query: Map<String, String> = emptyMap(),
        pageNumber: Int = 1,
    ): PublicListResult<PublicListDirectoryPage> =
        when (val response = accountResponse(path, query, pageNumber)) {
            is PublicListResult.Success -> directoryPage(response.value, parse)
            is PublicListResult.Failed -> response
            is PublicListResult.Unsupported -> response
        }

    private fun directoryPage(
        response: TraktHttpResponse,
        parse: (JSONObject) -> PublicListSummary?,
    ): PublicListResult<PublicListDirectoryPage> {
        val array = runCatching { JSONArray(response.body) }.getOrNull()
            ?: return PublicListResult.Failed(SHAPE_CHANGED)
        return PublicListResult.Success(
            PublicListDirectoryPage(
                lists = parseSummaries(array, parse),
                hasMore = hasMorePage(response.headers),
                nextCursor = nextCursor(response.headers),
            ),
        )
    }

    private suspend fun accountResponse(
        path: String,
        query: Map<String, String> = emptyMap(),
        pageNumber: Int = 1,
    ): PublicListResult<TraktHttpResponse> {
        val token = readToken() ?: return PublicListResult.Failed(UNAUTHORIZED)
        val request = TraktRequest(
            method = TraktMethod.GET,
            path = path,
            query = query + mapOf("limit" to pageSize.toString(), "page" to pageNumber.toString()),
            authenticated = true,
            readBudget = TraktReadBudget.LOOKUP,
        )
        val first = client.execute(request, token)
        if (first !is TraktOutcome.Unauthorized) return responseOutcome(first)
        val refreshed = readRefreshedToken() ?: return PublicListResult.Failed(ACCOUNT_REJECTED)
        return when (val second = client.execute(request, refreshed)) {
            is TraktOutcome.Success -> PublicListResult.Success(second.response)
            is TraktOutcome.Unauthorized -> PublicListResult.Failed(ACCOUNT_REJECTED)
            is TraktOutcome.Retry -> PublicListResult.Failed(second.reason)
            is TraktOutcome.Rejected -> PublicListResult.Failed(second.reason)
        }
    }

    private fun responseOutcome(outcome: TraktOutcome): PublicListResult<TraktHttpResponse> = when (outcome) {
        is TraktOutcome.Success -> PublicListResult.Success(outcome.response)
        is TraktOutcome.Unauthorized -> PublicListResult.Failed(UNAUTHORIZED)
        is TraktOutcome.Retry -> PublicListResult.Failed(outcome.reason)
        is TraktOutcome.Rejected -> PublicListResult.Failed(outcome.reason)
    }

    private suspend fun readToken(): String? = try {
        accountToken()?.takeIf { it.isNotBlank() }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    private suspend fun readRefreshedToken(): String? = try {
        refreshAccountToken()?.takeIf { it.isNotBlank() }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    private fun parseSummaries(
        array: JSONArray,
        parse: (JSONObject) -> PublicListSummary?,
    ): List<PublicListSummary> {
        val out = ArrayList<PublicListSummary>(array.length())
        for (index in 0 until array.length()) {
            parse(array.optJSONObject(index) ?: continue)?.let { out += it }
        }
        return out
    }

    companion object {

        const val DISCOVERY_PAGE_SIZE: Int = 24

        internal const val SHAPE_CHANGED = "trakt_response_shape_changed"

        internal const val UNAUTHORIZED = "unauthorized"

        internal const val ACCOUNT_REJECTED = "account_rejected"

        internal fun parseUsers(array: JSONArray): List<PublicListUser> {
            val out = ArrayList<PublicListUser>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                if (entry.optString("type", "") != "user") continue
                val user = entry.optJSONObject("user") ?: continue
                val username = user.optStringOrNull("username")
                    ?: user.optJSONObject("ids")?.optStringOrNull("slug")
                    ?: continue
                out += PublicListUser(
                    provider = PublicListProvider.TRAKT,
                    username = username,
                    displayName = user.optStringOrNull("name"),
                )
            }
            return out
        }

        internal fun parseItems(array: JSONArray): List<PublicListItem> {
            val out = ArrayList<PublicListItem>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val type = entry.optString("type", "")
                val payload = entry.optJSONObject(type) ?: continue
                val ids = payload.optJSONObject("ids")
                val kind = when (type) {
                    "movie" -> TitleKind.MOVIE
                    "show" -> TitleKind.SERIES

                    else -> continue
                }
                val title = payload.optString("title", "").trim()
                if (title.isEmpty()) continue
                val tmdb = ids?.optLongOrNull("tmdb")
                val imdb = ids?.optStringOrNull("imdb")
                val tvdb = ids?.optLongOrNull("tvdb")
                out += PublicListItem(
                    providerItemId = ids?.optString("trakt", "").orEmpty().ifBlank { "$index" },
                    title = title,
                    year = payload.optIntOrNull("year"),
                    kind = kind,
                    tmdbId = tmdb,
                    imdbId = imdb,
                    tvdbId = tvdb,
                    rank = entry.optIntOrNull("rank"),
                )
            }
            return out
        }

        internal fun hasMorePage(headers: Map<String, String>): Boolean {
            val page = header(headers, "X-Pagination-Page")?.toIntOrNull() ?: return false
            val pageCount = header(headers, "X-Pagination-Page-Count")?.toIntOrNull() ?: return false
            return page < pageCount
        }

        internal fun nextCursor(headers: Map<String, String>): String? {
            if (!hasMorePage(headers)) return null
            val page = header(headers, "X-Pagination-Page")?.toIntOrNull() ?: return null
            return "page:${page + 1}"
        }

        private fun header(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}

internal fun String?.toPageNumber(): Int =
    this?.substringAfter("page:", "")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

private fun traktListEntry(node: JSONObject): PublicListSummary? {

    val type = node.optString("type", "list")
    if (type != "list") return null
    return traktListObject(node.optJSONObject("list") ?: return null)
}

private fun traktListObject(list: JSONObject): PublicListSummary? {
    val ids = list.optJSONObject("ids") ?: return null
    val traktId = ids.optLongOrNull("trakt") ?: return null
    val title = list.optString("name", "").trim()
    if (title.isEmpty()) return null
    val user = list.optJSONObject("user")
    val owner = user?.optStringOrNull("username")
    return PublicListSummary(
        ref = PublicListRef(
            provider = PublicListProvider.TRAKT,
            listId = traktId.toString(),
            owner = owner,
            slug = ids.optStringOrNull("slug"),
        ),
        title = title,
        owner = owner,
        itemCount = list.optIntOrNull("item_count"),
    )
}
