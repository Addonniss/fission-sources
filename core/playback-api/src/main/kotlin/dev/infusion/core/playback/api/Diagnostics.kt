package dev.infusion.core.playback.api

data class EngineId(
    val name: String,
    val version: String,
) {
    init {
        require(name.isNotBlank()) { "engine name must not be blank" }
        require(version.isNotBlank()) { "engine version must not be blank" }
    }

    override fun toString(): String = "$name/$version"
}

enum class DecoderKind { VIDEO, AUDIO }

data class DecoderObservation(
    val kind: DecoderKind,

    val name: String? = null,

    val isHardwareAccelerated: Boolean? = null,
)

data class RedactionPolicy(
    val excludedFields: Set<String> = DEFAULT_EXCLUDED_FIELDS,
) {
    companion object {
        val DEFAULT_EXCLUDED_FIELDS: Set<String> = setOf(
            "uri", "headers", "cookie", "authorization", "token", "secret", "password",
        )
    }
}

data class DiagnosticsSnapshot(
    val sessionId: SessionId,
    val lineage: SessionLineage,
    val engineId: EngineId,
    val state: PlaybackState,
    val positionMs: Long? = null,
    val timeline: PlaybackTimeline? = null,
    val buffering: BufferingState? = null,
    val videoSize: VideoSize? = null,

    val firstFrameRenderedAtMs: Long? = null,
    val droppedFrames: Int? = null,
    val decoderObservations: List<DecoderObservation> = emptyList(),
    val capabilityObservations: List<CapabilityObservation> = emptyList(),
    val trackInventory: TrackInventory? = null,
    val lastFailure: PlaybackFailure? = null,
    val lastCancellationReason: CancellationReason? = null,

    val audioOutputProgress: AudioOutputProgress = AudioOutputProgress.NONE,
    val redaction: RedactionPolicy = RedactionPolicy(),
    val createdAtMs: Long,
) {

    fun isRedacted(secretTokens: List<String> = listOf("http://", "https://")): Boolean {
        val text = toString()
        return secretTokens.none { text.contains(it) }
    }
}
