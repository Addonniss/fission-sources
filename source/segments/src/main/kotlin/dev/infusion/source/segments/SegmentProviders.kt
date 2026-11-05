package dev.infusion.source.segments

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.UrlParser
import dev.infusion.core.playback.api.PlaybackSegment
import dev.infusion.core.playback.api.SegmentEvidence
import dev.infusion.core.playback.api.SegmentEvidenceSource
import dev.infusion.core.playback.api.SegmentLookupIdentity
import dev.infusion.core.playback.api.SegmentProvenance
import dev.infusion.core.playback.api.SegmentSourceOutcome
import dev.infusion.core.playback.api.SegmentType
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class SegmentHttp(private val client: HardenedHttpClient) {

    data class Answer(val status: Int, val body: String)

    suspend fun get(url: String): Answer? = withContext(Dispatchers.IO) {
        try {
            val canonical: CanonicalUrl = UrlParser.parse(url)
            client.execute(
                HardenedRequest(
                    url = canonical,
                    method = "GET",
                    limits = LIMITS,
                ),
            ).use { response ->
                val body = runCatching {
                    response.body.readBytes().toString(StandardCharsets.UTF_8)
                }.getOrDefault("")
                Answer(response.statusCode, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {

        val LIMITS: NetworkLimits = NetworkLimits(
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

class TheIntroDbSegmentSource(
    client: HardenedHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SegmentEvidenceSource {

    private val http = SegmentHttp(client)

    override val id: String = "theintrodb"

    override suspend fun fetch(identity: SegmentLookupIdentity): SegmentSourceOutcome {
        if (!identity.hasTheIntroDbCoordinates) return SegmentSourceOutcome.Absent
        val season = identity.seasonNumber ?: return SegmentSourceOutcome.Absent
        val episode = identity.episodeNumber ?: return SegmentSourceOutcome.Absent
        val url = buildString {
            append(baseUrl)
            append("?season=").append(season)
            append("&episode=").append(episode)
            when {
                identity.tmdbId != null -> append("&tmdb_id=").append(identity.tmdbId)
                !identity.imdbId.isNullOrBlank() -> append("&imdb_id=").append(identity.imdbId)
                else -> return SegmentSourceOutcome.Absent
            }
        }
        val answer = http.get(url) ?: return SegmentSourceOutcome.Failed("theintrodb-unreachable")
        return when (answer.status) {
            200 -> parse(answer.body)
            404 -> SegmentSourceOutcome.Absent
            else -> SegmentSourceOutcome.Failed("theintrodb-http-${answer.status}")
        }
    }

    internal fun parse(body: String): SegmentSourceOutcome {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return SegmentSourceOutcome.Failed("theintrodb-invalid-json")
        }
        val segments = mutableListOf<PlaybackSegment>()
        segments += arraySegments(root, "intro", SegmentType.INTRO)
        segments += arraySegments(root, "recap", SegmentType.RECAP)
        segments += arraySegments(root, "credits", SegmentType.CREDITS)
        segments += arraySegments(root, "preview", SegmentType.PREVIEW)
        return if (segments.isEmpty()) {
            SegmentSourceOutcome.Absent
        } else {
            SegmentSourceOutcome.Evidence(segments)
        }
    }

    private fun arraySegments(root: JSONObject, field: String, type: SegmentType): List<PlaybackSegment> {
        val array: JSONArray = root.optJSONArray(field) ?: return emptyList()
        val out = mutableListOf<PlaybackSegment>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val start = entry.optLongOrNull("start_ms") ?: continue
            val end = entry.optLongOrNull("end_ms")
            out += PlaybackSegment(
                type = type,
                startMs = start,
                endMs = end,
                provenance = SegmentProvenance.THEINTRODB,
                confidence = null,
                evidence = SegmentEvidence.Provider(provider = id, field = field),
            )
        }
        return out
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.theintrodb.org/v4/media"
    }
}

class IntroDbAppSegmentSource(
    client: HardenedHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SegmentEvidenceSource {

    private val http = SegmentHttp(client)

    override val id: String = "introdb"

    override suspend fun fetch(identity: SegmentLookupIdentity): SegmentSourceOutcome {
        if (!identity.hasIntroDbAppCoordinates) return SegmentSourceOutcome.Absent
        val showImdb = identity.showImdbId ?: return SegmentSourceOutcome.Absent
        val season = identity.seasonNumber ?: return SegmentSourceOutcome.Absent
        val episode = identity.episodeNumber ?: return SegmentSourceOutcome.Absent
        val url = "$baseUrl?imdb_id=$showImdb&season=$season&episode=$episode"
        val answer = http.get(url) ?: return SegmentSourceOutcome.Failed("introdb-unreachable")
        return when (answer.status) {
            200 -> parse(answer.body)
            400 -> SegmentSourceOutcome.Failed("introdb-invalid-query")
            else -> SegmentSourceOutcome.Failed("introdb-http-${answer.status}")
        }
    }

    internal fun parse(body: String): SegmentSourceOutcome {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return SegmentSourceOutcome.Failed("introdb-invalid-json")
        }
        val segments = mutableListOf<PlaybackSegment>()
        objectSegment(root, "intro", SegmentType.INTRO)?.let { segments += it }
        objectSegment(root, "recap", SegmentType.RECAP)?.let { segments += it }
        objectSegment(root, "outro", SegmentType.CREDITS)?.let { segments += it }
        objectSegment(root, "post_credits", SegmentType.POST_CREDITS_SCENE)?.let { segments += it }
        return if (segments.isEmpty()) {
            SegmentSourceOutcome.Absent
        } else {
            SegmentSourceOutcome.Evidence(segments)
        }
    }

    private fun objectSegment(root: JSONObject, field: String, type: SegmentType): PlaybackSegment? {
        val entry = root.optJSONObject(field) ?: return null
        val start = entry.optLongOrNull("start_ms") ?: entry.optLongOrNull("start_sec")?.times(1000L)
        ?: return null
        val end = entry.optLongOrNull("end_ms") ?: entry.optLongOrNull("end_sec")?.times(1000L)
        val confidence = entry.optDoubleOrNull("confidence")
        return PlaybackSegment(
            type = type,
            startMs = start,
            endMs = end,
            provenance = SegmentProvenance.INTRODB,
            confidence = confidence,
            evidence = SegmentEvidence.Provider(provider = id, field = field),
        )
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.introdb.app/segments"
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key)
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key)
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}
