package dev.infusion.tracking.simkl

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
import org.json.JSONArray
import org.json.JSONObject

class SimklPublicLists(
    private val client: SimklHttpClient,
    private val pageSize: Int = DISCOVERY_PAGE_SIZE,

    private val accountToken: suspend () -> String? = { null },

    private val isAccountConnected: () -> Boolean = { false },
) : PublicListDirectory, PublicListContentSource {

    override val provider: PublicListProvider = PublicListProvider.SIMKL

    override fun accountConnected(): Boolean = isAccountConnected()

    override fun capabilities(): PublicListCapabilities = PublicListCapabilities(
        provider = provider,
        routes = listOf(
            PublicListRouteSupport(PublicListRoute.MY_LISTS, "My Lists", requiresAccount = true),
            PublicListRouteSupport(
                PublicListRoute.USER_LISTS,
                "A user's lists",
                requiresAccount = true,
                inputLabel = "User ID",
            ),
        ),
        notes = listOf(
            "Simkl's list API is read-only and has no anonymous access: it needs the connected Simkl PRO/VIP account.",
            "Simkl exposes no list search, no user search and no list category; another user's lists are addressed by their numeric user id.",
        ),
    )

    override suspend fun lists(
        route: PublicListRoute,
        query: String?,
    ): PublicListResult<List<PublicListSummary>> = when (route) {

        PublicListRoute.MY_LISTS -> when (val userId = accountUserId()) {
            is PublicListResult.Success -> userLists(userId.value)
            is PublicListResult.Failed -> userId
            is PublicListResult.Unsupported -> userId
        }

        PublicListRoute.USER_LISTS -> PublicListResult.Unsupported("use the user lookup")
        PublicListRoute.POPULAR,
        PublicListRoute.TRENDING,
        PublicListRoute.CURATED,
        PublicListRoute.OFFICIAL,
        PublicListRoute.SEARCH_LISTS,
        PublicListRoute.SEARCH_USERS,
        PublicListRoute.LIKED_LISTS,
        -> PublicListResult.Unsupported("simkl serves no such list route")
    }

    override suspend fun users(query: String): PublicListResult<List<PublicListUser>> =
        PublicListResult.Unsupported("simkl exposes no user search")

    override suspend fun userLists(username: String): PublicListResult<List<PublicListSummary>> {
        val userId = username.trim().trimStart('@')
        if (userId.isEmpty()) return PublicListResult.Success(emptyList())
        return when (
            val body = send(
                SimklRequest(
                    method = SimklMethod.GET,
                    path = "/lists/user/$userId",
                    query = mapOf("limit" to pageSize.toString(), "page" to "1"),
                    readBudget = SimklReadBudget.LOOKUP,
                ),
            )
        ) {
            is PublicListResult.Success -> {
                val root = runCatching { JSONObject(body.value) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                val array = root.optJSONArray("lists")
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                PublicListResult.Success(parseSummaries(array))
            }
            is PublicListResult.Failed -> body
            is PublicListResult.Unsupported -> body
        }
    }

    override suspend fun page(ref: PublicListRef, cursor: String?): PublicListResult<PublicListPage> {
        val pageNumber = cursor.toSimklPageNumber()
        return when (
            val body = send(
                SimklRequest(
                    method = SimklMethod.GET,
                    path = "/lists/${ref.listId}",

                    query = mapOf("page" to pageNumber.toString(), "limit" to pageSize.toString()),
                    readBudget = SimklReadBudget.LOOKUP,
                ),
            )
        ) {
            is PublicListResult.Success -> {
                val root = runCatching { JSONObject(body.value) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                val array = root.optJSONArray("items")
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                val pagination = root.optJSONObject("pagination")
                val totalPages = pagination?.optIntOrNull("total_pages")
                val hasMore = totalPages != null && pageNumber < totalPages
                PublicListResult.Success(
                    PublicListPage(
                        items = parseItems(array),
                        hasMore = hasMore,
                        nextCursor = if (hasMore) "page:${pageNumber + 1}" else null,
                    ),
                )
            }
            is PublicListResult.Failed -> body
            is PublicListResult.Unsupported -> body
        }
    }

    private suspend fun accountUserId(): PublicListResult<String> =
        when (val body = send(SimklRequest(method = SimklMethod.GET, path = "/users/settings"))) {
            is PublicListResult.Success -> {
                val id = runCatching {
                    JSONObject(body.value).optJSONObject("account")?.optLongOrNull("id")
                }.getOrNull()
                if (id == null) PublicListResult.Failed(SHAPE_CHANGED) else PublicListResult.Success(id.toString())
            }
            is PublicListResult.Failed -> body
            is PublicListResult.Unsupported -> body
        }

    private suspend fun send(request: SimklRequest): PublicListResult<String> {
        val token = try {
            accountToken()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        if (token.isNullOrBlank()) return PublicListResult.Failed(UNAUTHORIZED)
        return when (val outcome = client.execute(request, token)) {
            is SimklOutcome.Success -> {
                val error = SimklHttpClient.errorCode(outcome.response.body)
                if (error == null) PublicListResult.Success(outcome.response.body) else PublicListResult.Failed(error)
            }
            is SimklOutcome.Unauthorized -> PublicListResult.Failed(UNAUTHORIZED)
            is SimklOutcome.Retry -> PublicListResult.Failed(outcome.reason)
            is SimklOutcome.Rejected -> PublicListResult.Failed(outcome.reason)
        }
    }

    companion object {

        const val DISCOVERY_PAGE_SIZE: Int = 100

        internal const val SHAPE_CHANGED = "simkl_response_shape_changed"

        internal const val UNAUTHORIZED = "unauthorized"

        internal const val PREMIUM_ONLY = "premium_only"

        internal fun parseSummaries(array: JSONArray): List<PublicListSummary> {
            val out = ArrayList<PublicListSummary>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val listId = entry.optLongOrNull("id") ?: continue
                val title = entry.optString("name", "").trim()
                if (title.isEmpty()) continue
                val owner = entry.optJSONObject("user")?.optStringOrNull("name")
                out += PublicListSummary(
                    ref = PublicListRef(
                        provider = PublicListProvider.SIMKL,
                        listId = listId.toString(),
                        owner = owner,
                        slug = entry.optStringOrNull("slug"),
                    ),
                    title = title,
                    owner = owner,
                    itemCount = entry.optJSONObject("counts")?.optIntOrNull("items"),
                )
            }
            return out
        }

        internal fun parseItems(array: JSONArray): List<PublicListItem> {
            val out = ArrayList<PublicListItem>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val title = entry.optString("title", "").trim()
                if (title.isEmpty()) continue
                val ids = entry.optJSONObject("ids")
                val type = entry.optString("type", "")
                out += PublicListItem(
                    providerItemId = (ids?.optLongOrNull("simkl_id") ?: ids?.optLongOrNull("simkl"))?.toString()
                        ?: ids?.optStringOrNull("slug")
                        ?: "$index",
                    title = title,
                    year = entry.optIntOrNull("year"),
                    kind = kindOf(type, entry.optStringOrNull("anime_type")),
                    tmdbId = ids?.optStringOrNull("tmdb")?.toLongOrNull(),
                    imdbId = ids?.optStringOrNull("imdb"),
                    tvdbId = ids?.optStringOrNull("tvdb")?.toLongOrNull(),
                    rank = entry.optIntOrNull("position"),
                )
            }
            return out
        }

        internal fun kindOf(type: String, animeType: String?): TitleKind? = when (type.lowercase()) {
            "movie" -> TitleKind.MOVIE
            "show", "tv" -> TitleKind.SERIES
            "anime" -> if (animeType?.equals("movie", ignoreCase = true) == true) {
                TitleKind.MOVIE
            } else {
                TitleKind.SERIES
            }
            else -> null
        }
    }
}

internal fun String?.toSimklPageNumber(): Int =
    this?.substringAfter("page:", "")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
