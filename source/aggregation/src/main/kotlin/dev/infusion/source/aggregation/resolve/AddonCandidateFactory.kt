package dev.infusion.source.aggregation.resolve

import dev.infusion.core.domain.candidate.CandidateFamily
import dev.infusion.core.model.CandidateFacts
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.FactConfidence
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.ProviderNotice
import dev.infusion.core.model.Reachability
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TransportDescriptor
import dev.infusion.source.stremio.wire.StremioStreamCandidate
import java.time.Instant
import java.util.UUID

class AddonCandidateFactory(
    private val titleId: TitleId,
    private val editionId: dev.infusion.core.model.EditionId?,
    private val sourceId: SourceId,
    private val nativeItemId: String,
    private val addonDisplayName: String,
    private val now: () -> Instant = Instant::now,
) {

    fun toCandidate(stream: StremioStreamCandidate): PlayableCandidate {
        val label = stream.name
            ?: (stream as? StremioStreamCandidate.Media)?.description
            ?: (stream as? StremioStreamCandidate.External)?.description
            ?: "Stream"

        val description = when (stream) {
            is StremioStreamCandidate.Media -> stream.description
            is StremioStreamCandidate.External -> stream.description
            is StremioStreamCandidate.Notice -> stream.description
        }
        val facts = parseFacts(stream)
        return when (stream) {
            is StremioStreamCandidate.Media -> PlayableCandidate(
                candidateId = CandidateId(UUID.nameUUIDFromBytes("$sourceId:$nativeItemId:${stream.url}".toByteArray())),
                titleId = titleId,
                editionId = editionId,
                sourceId = sourceId,
                nativeItemId = NativeItemId(sourceId, nativeItemId),
                familyId = CandidateFamily.derive(
                    NativeItemId(sourceId, nativeItemId),
                    versionId = stream.name ?: "default",
                ),
                providerName = addonDisplayName,
                originalLabel = label,
                providerDescription = description,
                transport = TransportDescriptor(stream.url.toString()),
                facts = facts,
                expectedMode = ExpectedMode.DIRECT,
                capability = null,
                expiresAt = null,
                reachability = Reachability.UNKNOWN,
                createdAt = now(),

                embeddedSubtitles = (stream as? StremioStreamCandidate.Media)
                    ?.subtitles
                    ?.map { sub ->
                        dev.infusion.core.model.EmbeddedSubtitle(
                            id = sub.id,
                            url = sub.url.toString(),
                            language = sub.lang,

                            format = sub.format,

                            sourceName = addonDisplayName,
                        )
                    }
                    .orEmpty(),
            )

            is StremioStreamCandidate.External -> PlayableCandidate(
                candidateId = CandidateId(UUID.nameUUIDFromBytes("$sourceId:$nativeItemId:ext:${stream.externalUrl}".toByteArray())),
                titleId = titleId,
                editionId = editionId,
                sourceId = sourceId,
                nativeItemId = NativeItemId(sourceId, nativeItemId),
                familyId = CandidateFamily.derive(
                    NativeItemId(sourceId, nativeItemId),
                    versionId = stream.name ?: "external",
                ),
                providerName = addonDisplayName,
                originalLabel = label,
                providerDescription = description,
                transport = TransportDescriptor(stream.externalUrl.toString()),
                facts = facts,
                expectedMode = ExpectedMode.EXTERNAL_LINK,
                capability = null,
                expiresAt = null,
                reachability = Reachability.UNKNOWN,
                createdAt = now(),
            )

            is StremioStreamCandidate.Notice ->
                error("provider notice is not a playable candidate")
        }
    }

    fun toNotice(stream: StremioStreamCandidate.Notice): ProviderNotice = ProviderNotice(
        providerName = addonDisplayName,
        title = stream.name ?: "No playable sources matched the provider filters",
        body = stream.description,
    )

    private fun parseFacts(stream: StremioStreamCandidate): CandidateFacts {
        val url = (stream as? StremioStreamCandidate.Media)?.url?.toString().orEmpty()
        val text = listOfNotNull(stream.name, (stream as? StremioStreamCandidate.Media)?.description)
            .joinToString(" ")
            .lowercase()
        val resolution = Regex("(4320|2160|1440|1080|720|576|480)p|4k|8k").find(text)?.value
        val hdr = listOf("hdr10", "dolby vision", "dv", "hlg").firstOrNull { it in text }
        val audio = listOf("ac3", "eac3", "dts", "truehd", "atmos", "aac", "mp3", "opus", "flac")
            .firstOrNull { it in text }

        val container = Regex("\\.(mkv|mp4|ts|webm|avi|mov|m4v)(\\?|$)").find(url.lowercase())?.groupValues?.get(1)

        val sizeBytes = (stream as? StremioStreamCandidate.Media)?.videoSizeBytes
            ?: parseScraperDisplaySizeBytes(text)
        val inferred = if (resolution != null || hdr != null || audio != null || container != null || sizeBytes != null) {
            FactConfidence.INFERRED
        } else {
            FactConfidence.UNKNOWN
        }
        return CandidateFacts(
            container = container,
            resolution = resolution,
            dynamicRange = hdr,
            audioCodec = audio,
            sizeBytes = sizeBytes,
            confidence = inferred,
            provenance = null,
        )
    }
}

internal fun parseScraperDisplaySizeBytes(text: String): Long? {
    val match = SIZE_TOKEN.find(text) ?: return null
    val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
    if (!value.isFinite() || value <= 0.0) return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "b" -> 1.0
        "kb" -> 1_000.0
        "kib" -> 1_024.0
        "mb" -> 1_000_000.0
        "mib" -> 1_048_576.0
        "gb" -> 1_000_000_000.0
        "gib" -> 1_073_741_824.0
        "tb" -> 1_000_000_000_000.0
        "tib" -> 1_099_511_627_776.0
        else -> return null
    }
    val bytes = value * multiplier

    if (bytes < 1.0 || bytes > MAX_DISPLAY_SIZE_BYTES) return null
    return bytes.toLong()
}

private const val MAX_DISPLAY_SIZE_BYTES = 1L shl 50

private val SIZE_TOKEN = Regex(
    """(\d{1,3}(?:,\d{3})+|\d{1,6}(?:\.\d+)?)[ ]?(b|kb|kib|mb|mib|gb|gib|tb|tib)(?![a-z0-9/])""",
    RegexOption.IGNORE_CASE,
)
