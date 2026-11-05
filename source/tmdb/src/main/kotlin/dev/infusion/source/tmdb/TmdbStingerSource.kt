package dev.infusion.source.tmdb

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.UrlParser
import dev.infusion.core.playback.api.StingerEvidence
import dev.infusion.core.playback.api.StingerEvidenceSource
import dev.infusion.core.playback.api.StingerLookupIdentity
import dev.infusion.core.playback.api.StingerSourceOutcome
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TmdbStingerSource(
    private val client: HardenedHttpClient,
    private val apiKey: TmdbApiKey,
    private val baseUrl: String = TMDB_API_ORIGIN,
) : StingerEvidenceSource {

    override val id: String = "tmdb"

    override suspend fun fetch(identity: StingerLookupIdentity): StingerSourceOutcome {
        if (!apiKey.isPresent) return StingerSourceOutcome.Failed("tmdb-no-credential")
        val movieId = identity.tmdbId ?: resolveMovieId(identity.imdbId) ?: return StingerSourceOutcome.Absent
        val url = "$baseUrl/movie/$movieId/keywords?api_key=${apiKey.reveal()}"
        val body = get(url) ?: return StingerSourceOutcome.Failed("tmdb-unreachable")
        return parse(body)
    }

    private suspend fun resolveMovieId(imdbId: String?): Long? {
        val imdb = imdbId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val url = "$baseUrl/find/$imdb?external_source=imdb_id&api_key=${apiKey.reveal()}"
        val body = get(url) ?: return null
        return try {
            JSONObject(body).optJSONArray("movie_results")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parse(body: String): StingerSourceOutcome {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return StingerSourceOutcome.Failed("tmdb-invalid-json")
        }
        val keywords = root.optJSONArray("keywords") ?: return StingerSourceOutcome.Absent
        var mid = false
        var post = false
        var bloopers = false
        for (i in 0 until keywords.length()) {
            val name = keywords.optJSONObject(i)?.optString("name", "").orEmpty().lowercase()
            when {
                name.contains("duringcreditsstinger") -> mid = true
                name.contains("aftercreditsstinger") -> post = true
                name.contains("blooper") || name.contains("outtake") -> bloopers = true
            }
        }
        if (!mid && !post && !bloopers) return StingerSourceOutcome.Absent
        return StingerSourceOutcome.Evidence(
            StingerEvidence(
                hasMidCredits = mid,
                hasPostCredits = post,
                hasBloopers = bloopers,
                source = "TMDB",
                definitive = true,
            ),
        )
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val canonical: CanonicalUrl = UrlParser.parse(url)
            client.execute(HardenedRequest(url = canonical, method = "GET", limits = LIMITS)).use { response ->
                if (response.statusCode != 200) return@withContext null
                runCatching { response.body.readBytes().toString(StandardCharsets.UTF_8) }.getOrDefault("")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    companion object {

        const val TMDB_API_ORIGIN: String = "https://api.themoviedb.org/3"

        private val LIMITS: NetworkLimits = NetworkLimits(
            maxResponseBytes = 1L * 1024 * 1024,
            maxDecompressedBytes = 1L * 1024 * 1024,
            maxDecompressionRatio = 4,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            totalTimeoutMs = 8_000,
            maxRedirects = 2,
        )
    }
}
