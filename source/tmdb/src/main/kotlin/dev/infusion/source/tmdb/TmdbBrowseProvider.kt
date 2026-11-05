package dev.infusion.source.tmdb

import dev.infusion.core.domain.collections.CollectionBrowseItem
import dev.infusion.core.domain.collections.CollectionBrowseOutcome
import dev.infusion.core.domain.collections.CollectionBrowsePage
import dev.infusion.core.domain.collections.CollectionRowPageSource
import dev.infusion.core.domain.collections.CollectionSourceDefinition
import dev.infusion.core.domain.metadata.MetadataFailureException
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleKind
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TmdbBrowseProvider(
    private val transport: HardenedHttpClient,
    private val apiKey: TmdbApiKey,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limits: NetworkLimits = TMDB_LIMITS,
    private val apiOrigin: String = TmdbEndpoints.API_ORIGIN,
) : CollectionRowPageSource {

    override suspend fun page(
        definition: CollectionSourceDefinition,
        cursor: String?,
        ownerKey: String?,
    ): CollectionBrowseOutcome {
        if (!definition.provider.equals(PROVIDER, ignoreCase = true)) {
            return CollectionBrowseOutcome.Unsupported(UNSUPPORTED_PROVIDER)
        }
        if (!apiKey.isPresent) return CollectionBrowseOutcome.Failed(NO_CREDENTIAL)
        val type = definition.tmdbSourceType?.trim()?.uppercase()
            ?: return CollectionBrowseOutcome.Unsupported(MISSING_TYPE)
        val page = cursor?.toPage() ?: 1
        return try {
            when (type) {
                "DISCOVER" -> discover(definition, page, companyId = null, networkId = null)
                "COMPANY" -> {
                    val id = definition.tmdbId?.takeIf { it.isNotBlank() }
                        ?: return CollectionBrowseOutcome.Unsupported(MISSING_ID)
                    discover(definition, page, companyId = id, networkId = null)
                }
                "NETWORK" -> {
                    val id = definition.tmdbId?.takeIf { it.isNotBlank() }
                        ?: return CollectionBrowseOutcome.Unsupported(MISSING_ID)
                    discover(definition, page, companyId = null, networkId = id)
                }
                "LIST" -> list(definition, page)
                "COLLECTION" -> collection(definition)
                else -> CollectionBrowseOutcome.Unsupported(UNSUPPORTED_TYPE)
            }
        } catch (error: MetadataFailureException) {
            CollectionBrowseOutcome.Failed(error.reason.name)
        } catch (error: NetworkPolicyException) {
            CollectionBrowseOutcome.Failed(error.reason.name)
        }
    }

    private suspend fun discover(
        definition: CollectionSourceDefinition,
        page: Int,
        companyId: String?,
        networkId: String?,
    ): CollectionBrowseOutcome {

        val kind = when {
            networkId != null -> TitleKind.SERIES
            else -> mediaKindOf(definition)
                ?: return CollectionBrowseOutcome.Unsupported(MISSING_MEDIA_TYPE)
        }
        val sort = discoverSort(definition)
            ?: return CollectionBrowseOutcome.Unsupported(ORDERING_UNSUPPORTED)
        val filters = mapFilters(definition, kind)
            ?: return CollectionBrowseOutcome.Unsupported(FILTER_UNSUPPORTED)

        val params = LinkedHashMap<String, String>()
        params["language"] = "en-US"
        params["page"] = page.coerceAtLeast(1).toString()
        params["sort_by"] = sort
        companyId?.let { params["with_companies"] = it }
        networkId?.let { params["with_networks"] = it }
        params.putAll(filters)

        val path = if (kind == TitleKind.SERIES) "/discover/tv" else "/discover/movie"
        val document = get(path, params, allowNotFound = true) ?: return emptyPage()
        val results = TmdbJson.arrayOrNull(document, "results") ?: return emptyPage()
        val items = buildList {
            TmdbJson.forEach(results) { entry -> add(entry.toBrowseItem(kind)) }
        }
        return loaded(document, results.length(), items)
    }

    private suspend fun list(definition: CollectionSourceDefinition, page: Int): CollectionBrowseOutcome {
        val id = definition.traktListId?.takeIf { it.isNotBlank() }
            ?: definition.tmdbId?.takeIf { it.isNotBlank() }
            ?: return CollectionBrowseOutcome.Unsupported(MISSING_ID)
        val params = LinkedHashMap<String, String>().apply {
            put("language", "en-US")
            put("page", page.coerceAtLeast(1).toString())
        }
        val document = get("/list/$id", params, allowNotFound = true) ?: return emptyPage()
        val results = TmdbJson.arrayOrNull(document, "items") ?: return emptyPage()
        val items = buildList {
            TmdbJson.forEach(results) { entry ->
                val kind = when (entry.optStringOrNull("media_type")?.lowercase()) {
                    "tv" -> TitleKind.SERIES
                    "movie" -> TitleKind.MOVIE

                    else -> null
                } ?: return@forEach
                add(entry.toBrowseItem(kind))
            }
        }
        return loaded(document, results.length(), items)
    }

    private suspend fun collection(definition: CollectionSourceDefinition): CollectionBrowseOutcome {
        val id = definition.tmdbId?.takeIf { it.isNotBlank() }
            ?: return CollectionBrowseOutcome.Unsupported(MISSING_ID)
        val params = LinkedHashMap<String, String>().apply { put("language", "en-US") }
        val document = get("/collection/$id", params, allowNotFound = true) ?: return emptyPage()
        val parts = TmdbJson.arrayOrNull(document, "parts") ?: return emptyPage()
        val items = buildList {
            TmdbJson.forEach(parts) { entry -> add(entry.toBrowseItem(TitleKind.MOVIE)) }
        }

        return CollectionBrowseOutcome.Loaded(CollectionBrowsePage(items = items, hasMore = false))
    }

    private fun JSONObject.toBrowseItem(kind: TitleKind): CollectionBrowseItem {
        val id = TmdbJson.long(this, "id")?.toString()
            ?: TmdbJson.string(this, "id")
            ?: return CollectionBrowseItem(
                providerItemId = "unknown",
                kind = kind,
                title = "",
            )
        val title = TmdbJson.string(this, "title")
            ?: TmdbJson.string(this, "name")
            ?: ""
        val date = TmdbJson.string(this, "release_date")
            ?: TmdbJson.string(this, "first_air_date")
        return CollectionBrowseItem(
            providerItemId = "$kind:$id",
            kind = kind,
            title = title,
            year = date?.trim()?.take(4)?.toIntOrNull(),
            tmdbId = TmdbJson.long(this, "id"),

            artwork = tmdbArtworkRef(
                posterPath = TmdbJson.string(this, "poster_path"),
                backdropPath = TmdbJson.string(this, "backdrop_path"),
            ),
        )
    }

    private fun tmdbArtworkRef(posterPath: String?, backdropPath: String?): MediaArtworkRef? {
        val poster = posterPath?.trim()?.takeIf { it.isNotEmpty() }
        val backdrop = backdropPath?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            poster != null -> MediaArtworkRef.metadata(
                provider = PROVIDER,
                path = poster,
                policy = ArtworkPolicy.POSTER,
                landscapePath = backdrop,
            )
            backdrop != null -> MediaArtworkRef.metadata(
                provider = PROVIDER,
                path = backdrop,
                policy = ArtworkPolicy.BACKDROP,
            )
            else -> null
        }
    }

    private fun loaded(document: JSONObject, pageSize: Int, items: List<CollectionBrowseItem>): CollectionBrowseOutcome {
        val page = TmdbJson.int(document, "page") ?: 1
        val totalPages = TmdbJson.int(document, "total_pages") ?: page
        val hasMore = totalPages > page && pageSize > 0
        return CollectionBrowseOutcome.Loaded(
            CollectionBrowsePage(
                items = items,
                hasMore = hasMore,
                nextCursor = if (hasMore) "p:${page + 1}" else null,
            ),
        )
    }

    private fun emptyPage(): CollectionBrowseOutcome =
        CollectionBrowseOutcome.Loaded(CollectionBrowsePage(items = emptyList(), hasMore = false))

    private fun mediaKindOf(definition: CollectionSourceDefinition): TitleKind? =
        when (definition.mediaType?.trim()?.uppercase()) {
            "MOVIE", "MOVIES" -> TitleKind.MOVIE
            "TV", "SERIES", "SHOW", "SHOWS" -> TitleKind.SERIES
            else -> null
        }

    private fun discoverSort(definition: CollectionSourceDefinition): String? {
        val raw = definition.sortBy ?: definition.filters[SORT_BY_FILTER_KEY]
        val value = raw?.trim()?.lowercase() ?: return null
        return value.takeIf { it in DISCOVER_SORTS }
    }

    private fun mapFilters(definition: CollectionSourceDefinition, kind: TitleKind): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        definition.filters.forEach { (key, value) ->

            if (key == SORT_BY_FILTER_KEY || key == SORT_HOW_FILTER_KEY) return@forEach
            val tmdbKey = tmdbFilterKey(key, kind) ?: return null
            out[tmdbKey] = value
        }
        return out
    }

    private fun tmdbFilterKey(key: String, kind: TitleKind): String? {
        val series = kind == TitleKind.SERIES
        return when (key) {

            "with_genres", "without_genres", "with_keywords", "without_keywords",
            "with_watch_providers", "watch_region", "with_original_language",
            "with_origin_country", "with_companies", "with_networks", "region",
            "vote_count.gte", "vote_count.lte", "vote_average.gte", "vote_average.lte",
            -> key
            "release_date.gte", "first_air_date.gte" -> if (series) "first_air_date.gte" else "release_date.gte"
            "release_date.lte", "first_air_date.lte" -> if (series) "first_air_date.lte" else "release_date.lte"
            "first_air_date_year" -> "first_air_date_year"

            "withGenres" -> "with_genres"
            "withoutGenres" -> "without_genres"
            "withKeywords" -> "with_keywords"
            "withoutKeywords" -> "without_keywords"
            "withWatchProviders" -> "with_watch_providers"
            "watchRegion" -> "watch_region"
            "withOriginalLanguage" -> "with_original_language"
            "withOriginCountry" -> "with_origin_country"
            "withCompanies" -> "with_companies"
            "withNetworks" -> "with_networks"
            "voteCountGte" -> "vote_count.gte"
            "voteCountLte" -> "vote_count.lte"
            "voteAverageGte" -> "vote_average.gte"
            "voteAverageLte" -> "vote_average.lte"
            "releaseDateGte" -> if (series) "first_air_date.gte" else "release_date.gte"
            "releaseDateLte" -> if (series) "first_air_date.lte" else "release_date.lte"
            "year" -> if (series) "first_air_date_year" else "year"
            else -> null
        }
    }

    private suspend fun get(
        path: String,
        params: Map<String, String>,
        allowNotFound: Boolean,
    ): JSONObject? = withContext(dispatcher) {
        val query = buildString {
            append("language=").append(encode(params["language"] ?: "en-US"))
            params.forEach { (key, value) ->
                if (key == "language") return@forEach
                append('&').append(encode(key)).append('=').append(encode(value))
            }
        }
        val url = try {
            UrlParser.parse("$apiOrigin${TmdbEndpoints.API_BASE_PATH}$path?$query&api_key=${apiKey.reveal()}")
        } catch (e: NetworkPolicyException) {
            throw e.toMetadataFailure()
        }
        val response = transport.execute(HardenedRequest(url = url, method = "GET", limits = limits))
        response.use { resp ->
            if (resp.statusCode !in 200..299) {
                if (allowNotFound && resp.statusCode == 404) return@withContext null
                throw httpStatusFailure(resp.statusCode)
            }
            val bytes = resp.body.readBytes()
            TmdbJson.parseDocument(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun String.toPage(): Int? =
        removePrefix("p:").toIntOrNull()?.takeIf { it > 0 }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    companion object {
        const val PROVIDER = "tmdb"

        internal const val SORT_BY_FILTER_KEY = "sortBy"
        internal const val SORT_HOW_FILTER_KEY = "sortHow"

        internal val DISCOVER_SORTS: Set<String> = setOf(
            "popularity.asc", "popularity.desc",
            "revenue.asc", "revenue.desc",
            "primary_release_date.asc", "primary_release_date.desc",
            "first_air_date.asc", "first_air_date.desc",
            "vote_average.asc", "vote_average.desc",
            "vote_count.asc", "vote_count.desc",
            "name.asc", "name.desc",
            "original_title.asc", "original_title.desc",
        )

        internal const val UNSUPPORTED_PROVIDER = "not a TMDB source"
        internal const val UNSUPPORTED_TYPE = "TMDB source type is not supported"
        internal const val MISSING_TYPE = "TMDB source type is missing"
        internal const val MISSING_ID = "TMDB source id is missing"
        internal const val MISSING_MEDIA_TYPE = "TMDB source media type is missing"
        internal const val ORDERING_UNSUPPORTED = "TMDB ordering cannot be preserved"
        internal const val FILTER_UNSUPPORTED = "a TMDB filter cannot be preserved"
        internal const val NO_CREDENTIAL = "no TMDB credential is configured"
    }
}
