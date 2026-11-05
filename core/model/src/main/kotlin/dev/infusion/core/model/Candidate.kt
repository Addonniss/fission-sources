package dev.infusion.core.model

import java.time.Instant

enum class FactConfidence {
    CONFIRMED,
    INFERRED,
    UNKNOWN,
}

enum class ExpectedMode {
    DIRECT,
    REMUX,
    TRANSCODE,
    EXTERNAL_LINK,
    UNRESOLVED,
}

enum class Reachability {
    REACHABLE,
    UNREACHABLE,
    UNKNOWN,
}

@JvmInline
value class TransportDescriptor(val value: String) {
    init {
        require(value.isNotBlank()) { "transport descriptor must not be blank" }
    }

    override fun toString(): String = "<transport>"
}

data class CandidateFacts(
    val container: String? = null,
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val resolution: String? = null,
    val dynamicRange: String? = null,
    val frameRate: Double? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val subtitleFormats: Set<String> = emptySet(),
    val language: String? = null,
    val sizeBytes: Long? = null,
    val bitrateBps: Long? = null,
    val confidence: FactConfidence = FactConfidence.UNKNOWN,
    val provenance: Provenance? = null,
)

data class EmbeddedSubtitle(

    val id: String,

    val url: String,

    val language: String? = null,

    val format: String? = null,

    val sourceName: String? = null,
)

data class PlayableCandidate(
    val candidateId: CandidateId,
    val titleId: TitleId,
    val editionId: EditionId? = null,
    val sourceId: SourceId,
    val nativeItemId: NativeItemId,
    val familyId: CandidateFamilyId,
    val providerName: String,
    val originalLabel: String,

    val providerDescription: String? = null,
    val transport: TransportDescriptor,
    val facts: CandidateFacts = CandidateFacts(),
    val expectedMode: ExpectedMode = ExpectedMode.UNRESOLVED,
    val capability: CapabilityAssessment? = null,
    val expiresAt: Instant? = null,
    val reachability: Reachability = Reachability.UNKNOWN,
    val rankExplanation: String? = null,
    val createdAt: Instant = Instant.now(),

    val durationMs: Long? = null,

    val embeddedSubtitles: List<EmbeddedSubtitle> = emptyList(),
) {
    init {
        require(providerName.isNotBlank()) { "providerName must not be blank" }
        require(originalLabel.isNotBlank()) { "originalLabel must not be blank" }
    }
}
