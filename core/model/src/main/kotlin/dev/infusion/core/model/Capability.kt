package dev.infusion.core.model

import java.time.Instant

enum class CapabilityState {
    SUPPORTED,
    DEGRADED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class RemedyKind {
    SELECT_OTHER_CANDIDATE,
    REFRESH_SOURCE,
    CHANGE_AUDIO_ROUTE,
    CHANGE_VIDEO_MODE,
    CHANGE_SUBTITLE_MODE,
    RETRY,
    UNKNOWN,
}

data class Remedy(
    val kind: RemedyKind,
    val description: String,
) {
    init {
        require(description.isNotBlank()) { "description must not be blank" }
    }
}

data class CapabilityAssessment(
    val state: CapabilityState = CapabilityState.UNKNOWN,
    val reasons: List<String> = emptyList(),
    val remedies: List<Remedy> = emptyList(),
    val routeSnapshotRef: String? = null,
    val assessedAt: Instant = Instant.now(),
)
