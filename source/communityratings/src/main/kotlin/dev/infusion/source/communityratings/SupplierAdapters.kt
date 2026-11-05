package dev.infusion.source.communityratings

import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.UrlParser
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal data class RatingLookup(
    val type: MediaType,
    val imdbId: String?,
    val tmdbId: Long?,
    val tvdbId: Long?,
)

internal class SupplierHttp(private val client: HardenedHttpClient) {

    data class Answer(val status: Int, val body: String)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        sensitiveHeaders: Set<String> = emptySet(),
    ): Answer? = try {
        val canonical = UrlParser.parse(url)
        client.execute(
            HardenedRequest(
                url = canonical,
                method = "GET",
                headers = headers,
                limits = LIMITS,
                sensitiveHeaders = sensitiveHeaders,
            ),
        ).use { response ->
            val body = runCatching {
                response.body.readBytes().toString(StandardCharsets.UTF_8)
            }.getOrDefault("")
            Answer(response.statusCode, body)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    companion object {

        val LIMITS: NetworkLimits = NetworkLimits(
            maxResponseBytes = 1L * 1024 * 1024,
            maxDecompressedBytes = 1L * 1024 * 1024,
            maxDecompressionRatio = 4,
            connectTimeoutMs = 8_000,
            readTimeoutMs = 8_000,
            totalTimeoutMs = 12_000,
            maxRedirects = 2,
        )
    }
}

internal interface SupplierAdapter {
    val supplier: RatingSupplier

    val metrics: Set<RatingMetric>

    val episodeMetrics: Set<RatingMetric> get() = emptySet()

    fun available(): Boolean

    suspend fun fetch(lookup: RatingLookup): List<MediaRating>?

    suspend fun fetchSeasonEpisodes(
        lookup: RatingLookup,
        seasonNumber: Int,
    ): Map<Int, List<MediaRating>>? = null
}

private fun canonicalMetric(id: String?): RatingMetric? = when (id?.trim()?.lowercase()) {
    "imdb", "internet movie database" -> RatingMetric.IMDB
    "tmdb" -> RatingMetric.TMDB
    "trakt" -> RatingMetric.TRAKT
    "rotten tomatoes", "tomatoes", "rt_critics", "rt" -> RatingMetric.RT_CRITICS
    "rotten_tomatoes_audience", "rt_audience", "audience" -> RatingMetric.RT_AUDIENCE
    "metacritic", "metascore" -> RatingMetric.METACRITIC
    "letterboxd" -> RatingMetric.LETTERBOXD
    "simkl" -> RatingMetric.SIMKL
    "mal", "myanimelist" -> RatingMetric.MAL

    else -> null
}

internal fun nativeValue(metric: RatingMetric, raw: Double): Double? {
    if (!raw.isFinite() || raw <= 0.0) return null
    val max = metric.defaultMax
    if (raw <= max) return raw
    if (max >= 100.0) return null
    val rescaled = raw / 100.0 * max
    return rescaled.takeIf { it in 0.0..max }
}

private fun rating(
    metric: RatingMetric,
    value: Double,
    votes: Int?,
    supplier: RatingSupplier,
): MediaRating? {
    if (!value.isFinite() || value <= 0.0) return null
    return MediaRating(
        source = metric.id,
        value = value,
        max = metric.defaultMax,
        votes = votes,
        label = metric.chipLabel,
        supplier = supplier.id,
    )
}

internal class OmdbAdapter(
    private val http: SupplierHttp,
    private val keyProvider: () -> String?,
) : SupplierAdapter {

    override val supplier: RatingSupplier = RatingSupplier.OMDB

    override val metrics: Set<RatingMetric> =
        setOf(RatingMetric.IMDB, RatingMetric.RT_CRITICS, RatingMetric.METACRITIC)

    override val episodeMetrics: Set<RatingMetric> = setOf(RatingMetric.IMDB)

    override fun available(): Boolean = !keyProvider().isNullOrBlank()

    override suspend fun fetch(lookup: RatingLookup): List<MediaRating>? {
        val imdb = lookup.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: return null
        val answer = http.get("$BASE?i=$imdb&apikey=$key&plot=short") ?: return null
        if (answer.status != 200) return null
        return parse(imdb, answer.body)
    }

    override suspend fun fetchSeasonEpisodes(
        lookup: RatingLookup,
        seasonNumber: Int,
    ): Map<Int, List<MediaRating>>? {
        if (lookup.type != MediaType.SHOW) return null
        val imdb = lookup.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: return null
        if (seasonNumber < 0) return null
        val answer = http.get("$BASE?i=$imdb&Season=$seasonNumber&apikey=$key") ?: return null
        if (answer.status != 200) return null
        return parseSeason(answer.body)
    }

    private fun parseSeason(body: String): Map<Int, List<MediaRating>>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        if (json.has("Error") && !json.optBoolean("Response", true)) return null
        if (!json.optBoolean("Response", true)) return null
        val episodes = json.optJSONArray("Episodes") ?: return null
        val out = LinkedHashMap<Int, List<MediaRating>>()
        for (index in 0 until episodes.length()) {
            val entry = episodes.optJSONObject(index) ?: continue
            val number = entry.optString("Episode").toIntOrNull() ?: continue

            val rating = rating(
                RatingMetric.IMDB,
                entry.optString("imdbRating").toDoubleOrNull() ?: -1.0,
                null,
                supplier,
            ) ?: continue
            out[number] = listOf(rating)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun parse(imdb: String, body: String): List<MediaRating>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        if (json.has("Error") && !json.optBoolean("Response", true)) return null
        if (!json.optBoolean("Response", true)) return null
        val byMetric = LinkedHashMap<RatingMetric, MediaRating>()

        fun put(candidate: MediaRating?) {
            candidate?.let { byMetric.putIfAbsent(it.ratedMetric ?: return@let, it) }
        }

        put(rating(RatingMetric.IMDB, json.optString("imdbRating").toDoubleOrNull() ?: -1.0, json.votesOrNull("imdbVotes"), supplier))
        put(rating(RatingMetric.METACRITIC, json.optString("Metascore").toDoubleOrNull() ?: -1.0, null, supplier))

        json.optJSONArray("Ratings")?.let { array ->
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val metric = canonicalMetric(entry.optString("Source")) ?: continue
                val value = parseScaledValue(entry.optString("Value"), metric.defaultMax) ?: continue
                put(rating(metric, value, null, supplier))
            }
        }
        return byMetric.values.takeIf { it.isNotEmpty() }?.toList()
    }

    private fun parseScaledValue(raw: String, max: Double): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.endsWith("%")) return text.dropLast(1).toDoubleOrNull()
        val slash = text.indexOf('/')
        val numeric = (if (slash >= 0) text.substring(0, slash) else text).trim().toDoubleOrNull() ?: return null

        val statedMax = if (slash >= 0) text.substring(slash + 1).trim().toDoubleOrNull() else null
        return when {
            statedMax == null || statedMax <= 0.0 -> numeric
            statedMax == max -> numeric
            else -> numeric / statedMax * max
        }
    }

    companion object {
        private const val BASE = "https://www.omdbapi.com/"
    }
}

internal class TraktAdapter(
    private val http: SupplierHttp,
    private val clientIdProvider: () -> String?,
) : SupplierAdapter {

    override val supplier: RatingSupplier = RatingSupplier.TRAKT

    override val metrics: Set<RatingMetric> = setOf(
        RatingMetric.TRAKT, RatingMetric.IMDB, RatingMetric.TMDB, RatingMetric.METACRITIC,
        RatingMetric.RT_CRITICS, RatingMetric.RT_AUDIENCE, RatingMetric.LETTERBOXD, RatingMetric.MAL,
    )

    override val episodeMetrics: Set<RatingMetric> = setOf(RatingMetric.TRAKT)

    override fun available(): Boolean = !clientIdProvider().isNullOrBlank()

    override suspend fun fetch(lookup: RatingLookup): List<MediaRating>? {
        val clientId = clientIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        val section = when (lookup.type) {
            MediaType.MOVIE -> "movies"
            MediaType.SHOW -> "shows"
        }
        val id = traktId(lookup) ?: return null
        val answer = http.get(
            "https://api.trakt.tv/$section/$id/ratings?extended=all",
            headers = mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId),
            sensitiveHeaders = setOf("trakt-api-key"),
        ) ?: return null
        if (answer.status != 200) return null
        return parse(answer.body)
    }

    override suspend fun fetchSeasonEpisodes(
        lookup: RatingLookup,
        seasonNumber: Int,
    ): Map<Int, List<MediaRating>>? {
        if (lookup.type != MediaType.SHOW) return null
        val clientId = clientIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        val id = traktId(lookup) ?: return null
        if (seasonNumber < 0) return null
        val answer = http.get(
            "https://api.trakt.tv/shows/$id/seasons/$seasonNumber/episodes?extended=full",
            headers = mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId),
            sensitiveHeaders = setOf("trakt-api-key"),
        ) ?: return null
        if (answer.status != 200) return null
        return parseSeasonEpisodes(answer.body)
    }

    private fun parseSeasonEpisodes(body: String): Map<Int, List<MediaRating>>? {
        val array = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return null
        val out = LinkedHashMap<Int, List<MediaRating>>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val number = entry.optInt("number", -1)
            if (number < 0) continue
            val rating = rating(
                RatingMetric.TRAKT,
                entry.optString("rating").toDoubleOrNull() ?: -1.0,
                entry.votesOrNull("votes"),
                supplier,
            ) ?: continue
            out[number] = listOf(rating)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun traktId(lookup: RatingLookup): String? = when {
        lookup.imdbId?.startsWith("tt", ignoreCase = true) == true -> lookup.imdbId
        lookup.tmdbId != null && lookup.tmdbId > 0 -> "tmdb-${lookup.tmdbId}"
        else -> null
    }

    private fun parse(body: String): List<MediaRating>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val out = LinkedHashMap<RatingMetric, MediaRating>()

        (externalMetric(json, "trakt", RatingMetric.TRAKT)
            ?: rating(
                RatingMetric.TRAKT,
                json.optString("rating").toDoubleOrNull() ?: -1.0,
                json.votesOrNull("votes"),
                supplier,
            ))?.let { out[RatingMetric.TRAKT] = it }

        externalMetric(json, "imdb", RatingMetric.IMDB)?.let { out.putIfAbsent(RatingMetric.IMDB, it) }
        externalMetric(json, "tmdb", RatingMetric.TMDB)?.let { out.putIfAbsent(RatingMetric.TMDB, it) }
        externalMetric(json, "metascore", RatingMetric.METACRITIC)?.let { out.putIfAbsent(RatingMetric.METACRITIC, it) }
        externalMetric(json, "metacritic", RatingMetric.METACRITIC)?.let { out.putIfAbsent(RatingMetric.METACRITIC, it) }
        externalMetric(json, "rotten_tomatoes", RatingMetric.RT_CRITICS)?.let { out.putIfAbsent(RatingMetric.RT_CRITICS, it) }

        json.optJSONObject("rotten_tomatoes")?.let { rt ->
            rating(
                RatingMetric.RT_AUDIENCE,
                rt.optString("user_rating").toDoubleOrNull() ?: -1.0,
                null,
                supplier,
            )?.let { out.putIfAbsent(RatingMetric.RT_AUDIENCE, it) }
        }
        externalMetric(json, "letterboxd", RatingMetric.LETTERBOXD)?.let { out.putIfAbsent(RatingMetric.LETTERBOXD, it) }
        externalMetric(json, "myanimelist", RatingMetric.MAL)?.let { out.putIfAbsent(RatingMetric.MAL, it) }
        return out.values.takeIf { it.isNotEmpty() }?.toList()
    }

    private fun externalMetric(root: JSONObject, key: String, metric: RatingMetric): MediaRating? {
        val obj = root.optJSONObject(key) ?: return null
        val value = obj.optString("rating").toDoubleOrNull() ?: return null
        return rating(metric, value, obj.votesOrNull("votes"), supplier)
    }
}

internal class SimklAdapter(
    private val http: SupplierHttp,
    private val clientIdProvider: () -> String?,
) : SupplierAdapter {

    override val supplier: RatingSupplier = RatingSupplier.SIMKL

    override val metrics: Set<RatingMetric> = setOf(
        RatingMetric.SIMKL, RatingMetric.IMDB, RatingMetric.TMDB,
    )

    override fun available(): Boolean = !clientIdProvider().isNullOrBlank()

    override suspend fun fetch(lookup: RatingLookup): List<MediaRating>? {
        val clientId = clientIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        val section = when (lookup.type) {
            MediaType.MOVIE -> "movies"
            MediaType.SHOW -> "tv"
        }
        val id = lookup.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: lookup.tmdbId?.takeIf { it > 0 }?.toString()
            ?: return null
        val answer = http.get("https://api.simkl.com/$section/$id?client_id=$clientId&extended=full")
            ?: return null
        if (answer.status != 200) return null
        return parse(answer.body)
    }

    private fun parse(body: String): List<MediaRating>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val ratings = json.optJSONObject("ratings") ?: return null
        val out = LinkedHashMap<RatingMetric, MediaRating>()
        val keys = ratings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val metric = canonicalMetric(key) ?: continue
            val obj = ratings.optJSONObject(key) ?: continue
            val value = obj.optString("rating").toDoubleOrNull() ?: continue
            rating(metric, value, obj.votesOrNull("votes"), supplier)?.let { out.putIfAbsent(metric, it) }
        }
        return out.values.takeIf { it.isNotEmpty() }?.toList()
    }
}

fun interface MdbListMediaInfoReader {
    suspend fun read(mediaProvider: String, mediaType: String, mediaId: String): String?
}

internal class MdbListAdapter(
    private val reader: MdbListMediaInfoReader?,
    private val connected: () -> Boolean,
) : SupplierAdapter {

    override val supplier: RatingSupplier = RatingSupplier.MDBLIST

    override val metrics: Set<RatingMetric> = setOf(
        RatingMetric.IMDB, RatingMetric.TMDB, RatingMetric.TRAKT, RatingMetric.METACRITIC,
        RatingMetric.RT_CRITICS, RatingMetric.RT_AUDIENCE, RatingMetric.LETTERBOXD, RatingMetric.MAL,
    )

    override fun available(): Boolean = reader != null && connected()

    override suspend fun fetch(lookup: RatingLookup): List<MediaRating>? {
        val info = reader ?: return null
        val (provider, id) = when {
            lookup.imdbId?.startsWith("tt", ignoreCase = true) == true -> "imdb" to lookup.imdbId
            lookup.tmdbId != null && lookup.tmdbId > 0 -> "tmdb" to lookup.tmdbId.toString()
            else -> return null
        }
        val type = when (lookup.type) {
            MediaType.MOVIE -> "movie"
            MediaType.SHOW -> "show"
        }
        val body = info.read(provider, type, id) ?: return null
        return parse(body)
    }

    private fun parse(body: String): List<MediaRating>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val array = json.optJSONArray("ratings") ?: return null
        val out = LinkedHashMap<RatingMetric, MediaRating>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val source = entry.optString("source")
                .ifBlank { entry.optString("provider") }
                .ifBlank { entry.optString("name") }
            val metric = canonicalMetric(source) ?: continue
            val raw = entry.optString("value").toDoubleOrNull()
                ?: entry.optString("rating").toDoubleOrNull()
                ?: continue

            val value = nativeValue(metric, raw) ?: continue
            rating(metric, value, entry.votesOrNull("votes"), supplier)?.let { out.putIfAbsent(metric, it) }
        }
        return out.values.takeIf { it.isNotEmpty() }?.toList()
    }
}

private fun JSONObject.votesOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toInt()
        else -> raw.toString().replace(",", "").trim().toIntOrNull()
    }
}
