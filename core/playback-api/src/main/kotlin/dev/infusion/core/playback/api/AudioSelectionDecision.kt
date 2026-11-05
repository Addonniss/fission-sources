package dev.infusion.core.playback.api

enum class AudioMatchReason {

    PRIMARY_EXACT,

    PRIMARY_BASE,

    SECONDARY_EXACT,

    SECONDARY_BASE,

    ROUTE_FALLBACK,
}

sealed interface AudioSelectionDecision {

    data class Select(val trackId: TrackId, val reason: AudioMatchReason) : AudioSelectionDecision

    data object LeaveToEngine : AudioSelectionDecision
}
