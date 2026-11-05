package dev.infusion.core.playback.api

enum class CapabilityState {
    SUPPORTED,
    DEGRADED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class CapabilityKind {
    VIDEO,
    AUDIO,
    SUBTITLE,
    CONTAINER,
    ROUTE,
    DRM,
    ADAPTIVE,
}

data class CapabilityObservation(
    val kind: CapabilityKind,
    val state: CapabilityState,

    val evidence: String,

    val remedy: String? = null,
    val timestampMs: Long,
) {
    init {
        require(evidence.isNotBlank()) { "evidence must not be blank" }
    }
}
