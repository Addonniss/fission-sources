package dev.infusion.source.tmdb

import dev.infusion.core.domain.metadata.MetadataFailureException
import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.domain.metadata.MediaSearchCandidate
import dev.infusion.core.domain.metadata.PersonSearchCandidate
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.metadata.CollectionDetails
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.MediaCardPage
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.PersonDetails
import dev.infusion.core.model.metadata.PersonId
import dev.infusion.core.model.metadata.ReviewPage
import dev.infusion.core.model.metadata.SeasonDetails
import dev.infusion.core.model.metadata.VideoKind
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TmdbMetadataProvider(
    private val transport: HardenedHttpClient,
    private val apiKey: TmdbApiKey,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limits: NetworkLimits = TMDB_LIMITS,

    private val apiOrigin: String = TmdbEndpoints.API_ORIGIN,
) : MetadataProvider {

    override val providerId: String = TmdbEndpoints.IMAGE_PROVIDER

    override suspend fun details(identity: MediaIdentity, locale: MetadataLocale): MediaDetails? {
        val tmdbId = requireTmdbId(identity)
        val path = detailsPath(identity.type, tmdbId)
        val mapper = TmdbMapper(locale)

        val primary = get(
            path,
            query(
                locale,
                "append_to_response" to facetsFor(identity.type),

                "include_image_language" to imageLanguage(locale),
            ),
        ) ?: return null
        var details = mapper.details(identity, primary, fallback = null)

        val missingImportantField = details.title.isBlank() ||
            details.overview.isNullOrBlank() ||
            (details.certification == null && locale.language != DEFAULT_LANGUAGE)
        val primaryVideos = mapper.videos(primary, fallback = null)
        val missingTrailer = primaryVideos.none { it.kind == VideoKind.TRAILER }
        val fallbackDocument = if (
            (missingImportantField || missingTrailer) && locale.language != DEFAULT_LANGUAGE
        ) {
            runCatching {
                get(
                    path,
                    query(
                        DEFAULT_LOCALE,
                        "append_to_response" to facetsFor(identity.type),

                        "include_image_language" to imageLanguage(DEFAULT_LOCALE),
                    ),
                )
            }.getOrNull()
        } else {
            null
        }
        if (fallbackDocument != null) {
            details = mapper.details(identity, primary, fallback = fallbackDocument)
        }

        val videos = if (missingTrailer) {

            mapper.videos(primary, fallback = fallbackDocument)
        } else {
            primaryVideos
        }
        return mapper.attachVideos(details, videos)
    }

    override suspend fun titledLandscape(
        identity: MediaIdentity,
        language: String,
    ): ImageRef? {
        val wanted = language.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) return null
        val tmdbId = requireTmdbId(identity)
        val document = get(
            detailsPath(identity.type, tmdbId),
            query(
                MetadataLocale(wanted),
                "append_to_response" to IMAGES_FACET,
                "include_image_language" to imageLanguage(MetadataLocale(wanted)),
            ),
        ) ?: return null
        return TmdbMapper(MetadataLocale(wanted)).titledLandscape(document, wanted)
    }

    override suspend fun season(
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): SeasonDetails? {
        val tmdbId = requireTmdbId(identity)
        if (identity.type != MediaType.SHOW) {
            throw MetadataFailureException(
                MetadataFailureReason.UNSUPPORTED,
                "seasons are only available for shows",
            )
        }
        val document = get(
            "/tv/$tmdbId/season/$seasonNumber",
            query(locale, "append_to_response" to "credits"),
            allowNotFound = true,
        ) ?: return null
        return TmdbMapper(locale).season(identity, document, seasonNumber)
    }

    override suspend fun collection(collectionId: Long, locale: MetadataLocale): CollectionDetails? {
        val document = get(
            "/collection/$collectionId",
            query(locale, "append_to_response" to "images"),
            allowNotFound = true,
        ) ?: return null
        return TmdbMapper(locale).collection(collectionId, document)
    }

    override suspend fun person(personId: PersonId, locale: MetadataLocale): PersonDetails? {
        val numeric = personId.value.toLongOrNull() ?: throw MetadataFailureException(
            MetadataFailureReason.UNSUPPORTED,
            "person id is not resolvable by this provider",
        )
        val document = get(
            "/person/$numeric",
            query(locale, "append_to_response" to "combined_credits"),
            allowNotFound = true,
        ) ?: return null
        return TmdbMapper(locale).person(personId, document)
    }

    override suspend fun recommendations(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MediaCardPage? = cardPage(identity, locale, page, "recommendations")

    override suspend fun similar(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MediaCardPage? = cardPage(identity, locale, page, "similar")

    override suspend fun reviews(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): ReviewPage? {
        val tmdbId = requireTmdbId(identity)
        val path = when (identity.type) {
            MediaType.MOVIE -> "/movie/$tmdbId/reviews"
            MediaType.SHOW -> "/tv/$tmdbId/reviews"
        }
        val document = get(
            path,
            query(locale, "page" to page.coerceAtLeast(1).toString()),
            allowNotFound = true,
        ) ?: return null
        return TmdbMapper(locale).reviewPage(document, page)
    }

    private suspend fun cardPage(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
        facet: String,
    ): MediaCardPage? {
        val tmdbId = requireTmdbId(identity)
        val path = when (identity.type) {
            MediaType.MOVIE -> "/movie/$tmdbId/$facet"
            MediaType.SHOW -> "/tv/$tmdbId/$facet"
        }
        val document = get(
            path,
            query(locale, "page" to page.coerceAtLeast(1).toString()),
            allowNotFound = true,
        ) ?: return null
        return TmdbMapper(locale).cardPage(document, identity.type, page)
    }

    override suspend fun resolveExternalId(
        external: TypedExternalId,
        type: MediaType,
        locale: MetadataLocale,
    ): ExternalIds? {
        val source = when (external.authority) {
            ExternalIds.AUTHORITY_IMDB -> "imdb_id"
            ExternalIds.AUTHORITY_TVDB -> "tvdb_id"
            else -> return null
        }
        val document = get(
            "/find/${external.value}",
            query(locale, "external_source" to source),
            allowNotFound = true,
        ) ?: return null
        val results = when (type) {
            MediaType.MOVIE -> TmdbJson.arrayOrNull(document, "movie_results")
            MediaType.SHOW -> TmdbJson.arrayOrNull(document, "tv_results")
        }
        val first = results?.optJSONObject(0) ?: return null
        val tmdbId = TmdbJson.long(first, "id") ?: return null

        return when (external.authority) {
            ExternalIds.AUTHORITY_IMDB -> ExternalIds(tmdbId = tmdbId, imdbId = external.value)
            else -> ExternalIds(tmdbId = tmdbId, tvdbId = external.value.toLongOrNull())
        }
    }

    override suspend fun search(
        title: String,
        year: Int?,
        type: MediaType,
        locale: MetadataLocale,
    ): List<MediaSearchCandidate> {
        if (title.isBlank()) return emptyList()
        val path = when (type) {
            MediaType.MOVIE -> "/search/movie"
            MediaType.SHOW -> "/search/tv"
        }
        val yearParam = when (type) {
            MediaType.MOVIE -> "year"
            MediaType.SHOW -> "first_air_date_year"
        }
        val document = get(
            path,
            query(locale, "query" to title, yearParam to year?.toString()),
            allowNotFound = true,
        ) ?: return emptyList()
        val results = TmdbJson.arrayOrNull(document, "results") ?: return emptyList()
        return buildList {
            TmdbJson.forEach(results) { entry ->
                val id = TmdbJson.long(entry, "id") ?: return@forEach
                if (id <= 0) return@forEach
                val name = TmdbJson.string(entry, "title") ?: TmdbJson.string(entry, "name") ?: return@forEach
                val date = TmdbJson.string(entry, "release_date") ?: TmdbJson.string(entry, "first_air_date")
                add(
                    MediaSearchCandidate(
                        identity = MediaIdentity(type = type, externalIds = ExternalIds(tmdbId = id)),
                        title = name,
                        year = date?.trim()?.take(4)?.toIntOrNull(),

                        artwork = TmdbJson.string(entry, "poster_path")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ImageRef(TmdbEndpoints.IMAGE_PROVIDER, it) },

                        landscapeArtwork = TmdbJson.string(entry, "backdrop_path")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ImageRef(TmdbEndpoints.IMAGE_PROVIDER, it) },
                    ),
                )
            }
        }.distinctBy { it.identity.cacheKey() }
    }

    override suspend fun searchPeople(
        term: String,
        locale: MetadataLocale,
    ): List<PersonSearchCandidate> {
        if (term.isBlank()) return emptyList()
        val document = get(
            "/search/person",
            query(locale, "query" to term, "include_adult" to "false"),
            allowNotFound = true,
        ) ?: return emptyList()
        val results = TmdbJson.arrayOrNull(document, "results") ?: return emptyList()
        return buildList {
            TmdbJson.forEach(results) { entry ->
                val id = TmdbJson.long(entry, "id") ?: return@forEach
                if (id <= 0) return@forEach
                val name = TmdbJson.string(entry, "name")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                add(
                    PersonSearchCandidate(
                        id = PersonId(TmdbEndpoints.IMAGE_PROVIDER, id.toString()),
                        name = name,
                        knownForDepartment = TmdbJson.string(entry, "known_for_department")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                        profile = TmdbJson.string(entry, "profile_path")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ImageRef(TmdbEndpoints.IMAGE_PROVIDER, it) },
                    ),
                )
            }
        }.distinctBy { it.id }
    }

    private fun requireTmdbId(identity: MediaIdentity): Long {
        if (!apiKey.isPresent) {
            throw MetadataFailureException(
                MetadataFailureReason.UNAUTHORIZED,
                "no metadata provider credential is configured",
            )
        }
        return identity.externalIds.tmdbId ?: throw MetadataFailureException(
            MetadataFailureReason.UNSUPPORTED,
            "the identity carries no canonical metadata anchor",
        )
    }

    private fun detailsPath(type: MediaType, tmdbId: Long): String = when (type) {
        MediaType.MOVIE -> "/movie/$tmdbId"
        MediaType.SHOW -> "/tv/$tmdbId"
    }

    private fun facetsFor(type: MediaType): String = when (type) {
        MediaType.MOVIE -> MOVIE_FACETS
        MediaType.SHOW -> TV_FACETS
    }

    private fun query(locale: MetadataLocale, vararg extra: Pair<String, String?>): String = buildString {
        append("language=")
        append(encode(locale.tag))
        for ((key, value) in extra) {
            if (value == null) continue
            append('&')
            append(encode(key))
            append('=')
            append(encode(value))
        }
    }

    private fun imageLanguage(locale: MetadataLocale): String =
        listOf(locale.language, TEXT_LESS_IMAGE_LANGUAGE).joinToString(",")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private suspend fun get(
        path: String,
        query: String,
        allowNotFound: Boolean = false,
    ): JSONObject? = withContext(dispatcher) {
        val url = try {
            UrlParser.parse("$apiOrigin${TmdbEndpoints.API_BASE_PATH}$path?$query&api_key=${apiKey.reveal()}")
        } catch (e: NetworkPolicyException) {
            throw e.toMetadataFailure()
        }
        val response = try {
            transport.execute(HardenedRequest(url = url, method = "GET", limits = limits))
        } catch (e: NetworkPolicyException) {
            throw e.toMetadataFailure()
        }
        response.use { resp ->
            if (resp.statusCode !in 200..299) {
                if (allowNotFound && resp.statusCode == 404) return@withContext null
                throw httpStatusFailure(resp.statusCode)
            }
            val bytes = try {
                resp.body.readBytes()
            } catch (e: NetworkPolicyException) {
                throw e.toMetadataFailure()
            } catch (e: java.io.IOException) {
                throw MetadataFailureException(
                    MetadataFailureReason.MALFORMED,
                    "metadata response could not be read",
                    cause = e,
                )
            }
            TmdbJson.parseDocument(bytes.toString(Charsets.UTF_8))
        }
    }

    private companion object {
        val DEFAULT_LOCALE: MetadataLocale = MetadataLocale.Default
        const val DEFAULT_LANGUAGE = "en"

        const val TEXT_LESS_IMAGE_LANGUAGE = "null"

        const val MOVIE_FACETS = "credits,external_ids,videos,keywords,release_dates,images"

        const val TV_FACETS = "aggregate_credits,credits,external_ids,videos,keywords,content_ratings,images"

        const val IMAGES_FACET = "images"
    }
}
