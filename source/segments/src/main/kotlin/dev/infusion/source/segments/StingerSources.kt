package dev.infusion.source.segments

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.UrlParser
import dev.infusion.core.playback.api.StingerEvidence
import dev.infusion.core.playback.api.StingerEvidenceSource
import dev.infusion.core.playback.api.StingerLookupIdentity
import dev.infusion.core.playback.api.StingerSourceOutcome
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AfterCreditsStingerSource(
    private val client: HardenedHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : StingerEvidenceSource {

    override val id: String = "aftercredits"

    override suspend fun fetch(identity: StingerLookupIdentity): StingerSourceOutcome {
        val title = identity.title?.trim()?.takeIf { it.isNotBlank() } ?: return StingerSourceOutcome.Absent

        val postId = searchPostId(searchQuery(title, identity.year))
            ?: identity.year?.let { searchPostId(title) }
            ?: return StingerSourceOutcome.Absent
        val detail = get("$baseUrl/$postId?_fields=content,_links,_embedded&_embed=wp:term")
            ?: return StingerSourceOutcome.Failed("aftercredits-detail-unreachable")
        return parseDetail(detail)
    }

    private suspend fun searchPostId(query: String): Long? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val body = get("$baseUrl?search=$encoded&_fields=id,title,link&per_page=10") ?: return null
        val posts = try {
            JSONArray(body)
        } catch (_: Exception) {
            return null
        }

        val cleaned = normalize(query)
        var fallback: Long? = null
        for (i in 0 until posts.length()) {
            val post = posts.optJSONObject(i) ?: continue
            val id = post.optLong("id", -1L).takeIf { it > 0L } ?: continue
            val rawTitle = normalize(post.optJSONObject("title")?.optString("rendered", "").orEmpty())
            if (rawTitle.isBlank()) continue
            val matches = rawTitle.contains(cleaned) || cleaned.contains(rawTitle)
            if (!matches) continue
            if (!rawTitle.contains("review")) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    internal fun parseDetail(body: String): StingerSourceOutcome {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return StingerSourceOutcome.Failed("aftercredits-invalid-json")
        }
        val categories = mutableSetOf<String>()
        val terms = root.optJSONObject("_embedded")?.optJSONArray("wp:term")
        if (terms != null) {
            for (i in 0 until terms.length()) {
                val taxonomy = terms.optJSONArray(i) ?: continue
                for (j in 0 until taxonomy.length()) {
                    val name = taxonomy.optJSONObject(j)?.optString("name", "").orEmpty()
                        .lowercase()
                        .replace("&amp;", "&")
                        .replace("&#038;", "&")
                        .replace("&#38;", "&")
                        .trim()
                    if (name.isNotEmpty()) categories += name
                }
            }
        }
        if ("non-stingers" in categories) {
            return StingerSourceOutcome.Evidence(
                StingerEvidence(source = "AfterCredits", definitive = true),
            )
        }

        if ("unknown" in categories) return StingerSourceOutcome.Absent
        val both = "both during & after credits" in categories
        val mid = both || "during credits" in categories
        val post = both || "after credits" in categories
        val bloopers = BLOOPER_TAGS.any { it in categories }
        if (!mid && !post && categories.isEmpty()) return StingerSourceOutcome.Absent
        return StingerSourceOutcome.Evidence(
            StingerEvidence(
                hasMidCredits = mid,
                hasPostCredits = post,
                hasBloopers = bloopers,
                source = "AfterCredits",
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
        const val DEFAULT_BASE_URL: String = "https://aftercredits.com/wp-json/wp/v2/posts"

        private val BLOOPER_TAGS = listOf("bloopers", "outtakes", "gag reel", "behind the scenes")

        private fun searchQuery(title: String, year: Int?): String =
            if (year != null && year > 0) "$title $year" else title

        private fun normalize(value: String): String = value.lowercase().trim().replace(Regex("\\s+"), " ")

        private val LIMITS: NetworkLimits = NetworkLimits(
            maxResponseBytes = 2L * 1024 * 1024,
            maxDecompressedBytes = 2L * 1024 * 1024,
            maxDecompressionRatio = 4,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            totalTimeoutMs = 8_000,
            maxRedirects = 2,
        )
    }
}
