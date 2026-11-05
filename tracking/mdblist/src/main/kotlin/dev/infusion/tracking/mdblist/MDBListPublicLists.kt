package dev.infusion.tracking.mdblist

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
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.UrlParser
import org.json.JSONArray
import org.json.JSONObject

class MDBListPublicLists(
    private val transport: MDBListTransport,
    private val baseUrl: String = DEFAULT_WEB_BASE_URL,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,

    private val accountToken: suspend () -> String? = { null },

    private val isAccountConnected: () -> Boolean = { false },
) : PublicListDirectory, PublicListContentSource {

    override val provider: PublicListProvider = PublicListProvider.MDBLIST

    override fun accountConnected(): Boolean = isAccountConnected()

    override fun capabilities(): PublicListCapabilities = PublicListCapabilities(
        provider = provider,
        routes = listOf(
            PublicListRouteSupport(PublicListRoute.POPULAR, "Popular Lists"),
            PublicListRouteSupport(PublicListRoute.CURATED, "Curated Lists"),
            PublicListRouteSupport(PublicListRoute.OFFICIAL, "Official Lists"),
            PublicListRouteSupport(
                PublicListRoute.USER_LISTS,
                "User's Public Lists",
                inputLabel = "Username",
            ),
            PublicListRouteSupport(PublicListRoute.MY_LISTS, "My Lists", requiresAccount = true),
            PublicListRouteSupport(PublicListRoute.LIKED_LISTS, "Liked Lists", requiresAccount = true),
        ),
        notes = listOf(
            "MDBList's public website has no list/user search; only its category pages and an exact user path are public.",
            "My Lists / Liked Lists are read from MDBList's credentialed API; list CONTENTS stay on the public JSON path.",
        ),
    )

    override suspend fun lists(
        route: PublicListRoute,
        query: String?,
    ): PublicListResult<List<PublicListSummary>> = when (route) {
        PublicListRoute.POPULAR -> discover("/toplists")
        PublicListRoute.CURATED -> discover("/curatedlists")
        PublicListRoute.OFFICIAL -> discover("/lists/official")
        PublicListRoute.MY_LISTS -> myLists()
        PublicListRoute.LIKED_LISTS -> likedLists()
        PublicListRoute.TRENDING,
        PublicListRoute.SEARCH_LISTS,
        PublicListRoute.SEARCH_USERS,
        PublicListRoute.USER_LISTS,
        -> PublicListResult.Unsupported("not exposed publicly by mdblist.com")
    }

    override suspend fun users(query: String): PublicListResult<List<PublicListUser>> =
        PublicListResult.Unsupported("mdblist.com exposes no public user search")

    override suspend fun userLists(username: String): PublicListResult<List<PublicListSummary>> {
        val handle = username.trim().trimStart('@')
        if (handle.isEmpty()) return PublicListResult.Success(emptyList())
        return discover("/lists/$handle/")
    }

    override suspend fun page(ref: PublicListRef, cursor: String?): PublicListResult<PublicListPage> {

        if (cursor != null) return PublicListResult.Failed("no such page")
        val path = "/lists/${ref.listId}/json/"
        return when (val response = get(path)) {
            is PublicListResult.Success -> {
                val array = runCatching { JSONArray(response.value) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                PublicListResult.Success(PublicListPage(items = parseItems(array)))
            }
            is PublicListResult.Failed -> response
            is PublicListResult.Unsupported -> response
        }
    }

    private suspend fun myLists(): PublicListResult<List<PublicListSummary>> {
        val owner = when (val account = accountGet("/user")) {
            is PublicListResult.Success -> {
                val username = runCatching { JSONObject(account.value).optStringOrNull("username") }.getOrNull()
                if (username.isNullOrBlank()) {
                    return PublicListResult.Failed(SHAPE_CHANGED)
                }
                username
            }
            is PublicListResult.Failed -> return account
            is PublicListResult.Unsupported -> return account
        }
        return when (val body = accountGet("/lists/user")) {
            is PublicListResult.Success -> {
                val array = runCatching { JSONArray(body.value) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                PublicListResult.Success(parseAccountSummaries(array, defaultOwner = owner))
            }
            is PublicListResult.Failed -> body
            is PublicListResult.Unsupported -> body
        }
    }

    private suspend fun likedLists(): PublicListResult<List<PublicListSummary>> =
        when (val body = accountGet("/lists/liked")) {
            is PublicListResult.Success -> {
                val root = runCatching { JSONObject(body.value) }.getOrNull()
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                val array = root.optJSONArray("lists")
                    ?: return PublicListResult.Failed(SHAPE_CHANGED)
                PublicListResult.Success(parseAccountSummaries(array, defaultOwner = null))
            }
            is PublicListResult.Failed -> body
            is PublicListResult.Unsupported -> body
        }

    private suspend fun accountGet(path: String): PublicListResult<String> {
        val token = try {
            accountToken()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        if (token.isNullOrBlank()) return PublicListResult.Failed(UNAUTHORIZED)
        val url: CanonicalUrl = UrlParser.parse("$apiBaseUrl$path")
        return try {
            val response = transport.execute(
                url = url,
                method = "GET",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json",

                    "Authorization" to "Bearer $token",
                ),
                body = null,
                limits = READ_LIMITS,
            )
            when {
                response.status in 200..299 -> PublicListResult.Success(response.body)
                response.status == 401 -> PublicListResult.Failed(UNAUTHORIZED)
                response.status == 403 -> PublicListResult.Failed("access_denied")
                response.status == 404 -> PublicListResult.Failed("not_found")
                response.status == 429 -> PublicListResult.Failed("rate_limit")
                response.status >= 500 -> PublicListResult.Failed("provider_unavailable")
                else -> PublicListResult.Failed("http_${response.status}")
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PublicListResult.Failed("transport_failure")
        }
    }

    private suspend fun discover(path: String): PublicListResult<List<PublicListSummary>> =
        when (val response = get(path)) {
            is PublicListResult.Success -> {
                val summaries = parseSummaries(response.value)

                if (summaries.isEmpty()) {
                    PublicListResult.Failed(SHAPE_CHANGED)
                } else {
                    PublicListResult.Success(summaries)
                }
            }
            is PublicListResult.Failed -> response
            is PublicListResult.Unsupported -> response
        }

    private suspend fun get(path: String): PublicListResult<String> {
        val url: CanonicalUrl = UrlParser.parse("$baseUrl$path")
        return try {
            val response = transport.execute(
                url = url,
                method = "GET",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/json;q=0.9",
                ),
                body = null,
                limits = READ_LIMITS,
            )
            when {
                response.status in 200..299 -> PublicListResult.Success(response.body)
                response.status == 404 -> PublicListResult.Failed("not_found")
                response.status == 401 || response.status == 403 -> PublicListResult.Failed("access_denied")
                response.status == 429 -> PublicListResult.Failed("rate_limit")
                response.status >= 500 -> PublicListResult.Failed("provider_unavailable")
                else -> PublicListResult.Failed("http_${response.status}")
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PublicListResult.Failed("transport_failure")
        }
    }

    companion object {

        const val DEFAULT_WEB_BASE_URL: String = "https://mdblist.com"

        const val DEFAULT_API_BASE_URL: String = "https://api.mdblist.com"

        const val DEFAULT_USER_AGENT: String = "Infusion/1.0"

        internal const val SHAPE_CHANGED = "mdblist_response_shape_changed"

        internal const val UNAUTHORIZED = "unauthorized"

        val READ_LIMITS: NetworkLimits = NetworkLimits.DEFAULT.copy(
            maxResponseBytes = 4L * 1024 * 1024,
            maxDecompressedBytes = 8L * 1024 * 1024,
            maxRequestBytes = 64L * 1024,
            totalTimeoutMs = 25_000L,
        )

        private val LIST_HREF = Regex("""href="/lists/([A-Za-z0-9_.%~-]+)/([A-Za-z0-9_.%~/-]+)"""")
        private val ALT_TITLE = Regex("""alt="([^"]{1,140}?) poster \d+"""")
        private val ANCHOR_TEXT = Regex("""href="/lists/[^"]+"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val ITEM_COUNT = Regex(""">(\d+)\s*items<""")

        private val CARD_ARTICLE =
            Regex("""<article\b[^>]*class="[^"]*related-list-card[^"]*"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
        private val CARD_TABLE =
            Regex("""<table\b[^>]*class="[^"]*striped table[^"]*"[^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)

        internal fun cardRegions(html: String): List<String> {
            val ranges = ArrayList<IntRange>(8)
            CARD_ARTICLE.findAll(html).forEach { ranges += it.range }
            CARD_TABLE.findAll(html).forEach { ranges += it.range }
            return ranges.sortedBy { it.first }.map { range -> html.substring(range) }
        }

        internal fun parseItems(array: JSONArray): List<PublicListItem> {
            val out = ArrayList<PublicListItem>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val title = entry.optString("title", "").trim()
                if (title.isEmpty()) continue
                val kind = when (entry.optString("mediatype", "")) {
                    "movie" -> TitleKind.MOVIE
                    "show" -> TitleKind.SERIES
                    else -> null
                }
                val tmdb = entry.optLongOrNull("id")
                val tvdb = entry.optLongOrNull("tvdbid")
                val imdb = entry.optStringOrNull("imdb_id")
                out += PublicListItem(
                    providerItemId = tmdb?.toString() ?: "$index",
                    title = title,
                    year = entry.optIntOrNull("release_year"),
                    kind = kind,
                    tmdbId = tmdb,
                    imdbId = imdb,
                    tvdbId = tvdb,
                    rank = entry.optIntOrNull("rank"),
                )
            }
            return out
        }

        internal fun parseAccountSummaries(
            array: JSONArray,
            defaultOwner: String?,
        ): List<PublicListSummary> {
            val out = ArrayList<PublicListSummary>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val title = entry.optString("name", "").trim()
                if (title.isEmpty()) continue
                val slug = entry.optStringOrNull("slug") ?: continue
                val owner = entry.optStringOrNull("user_name") ?: defaultOwner ?: continue
                val key = "$owner/$slug"
                out += PublicListSummary(
                    ref = PublicListRef(
                        provider = PublicListProvider.MDBLIST,
                        listId = key,
                        owner = owner,
                        slug = slug,
                    ),
                    title = title,
                    owner = owner,
                    itemCount = entry.optIntOrNull("items"),
                )
            }
            return out
        }

        internal fun parseSummaries(html: String): List<PublicListSummary> {
            val out = LinkedHashMap<String, PublicListSummary>()
            cardRegions(html).forEach { region ->
                LIST_HREF.findAll(region).forEach { match ->
                    val owner = match.groupValues[1]
                    val slug = match.groupValues[2].trim('/')
                    if (owner.isEmpty() || slug.isEmpty() || slug.endsWith("/json")) return@forEach
                    val key = "$owner/$slug"
                    if (out.containsKey(key)) return@forEach

                    val windowEnd = (match.range.first + TITLE_WINDOW).coerceAtMost(region.length)
                    val window = region.substring(match.range.first, windowEnd)
                    val title = cardTitle(window) ?: return@forEach
                    out[key] = PublicListSummary(
                        ref = PublicListRef(
                            provider = PublicListProvider.MDBLIST,
                            listId = key,
                            owner = owner,
                            slug = slug,
                        ),
                        title = title,
                        owner = owner,
                        itemCount = ITEM_COUNT.find(window)?.groupValues?.get(1)?.toIntOrNull(),
                    )
                }
            }
            return out.values.toList()
        }

        private const val TITLE_WINDOW = 3_000

        private fun cardTitle(window: String): String? {
            ALT_TITLE.find(window)?.let { match ->
                unescapeHtml(match.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return it }
            }
            ANCHOR_TEXT.find(window)?.let { match ->
                val text = match.groupValues[1].replace(Regex("<[^>]+>"), " ")
                val cleaned = unescapeHtml(text).replace(Regex("\\s+"), " ").trim()
                if (cleaned.isNotEmpty()) return cleaned
            }
            return null
        }

        internal fun unescapeHtml(value: String): String = value
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }
}
